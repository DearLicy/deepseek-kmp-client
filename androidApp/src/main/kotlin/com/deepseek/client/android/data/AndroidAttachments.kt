package com.deepseek.client.android.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.AtomicFile
import androidx.core.content.FileProvider
import com.deepseek.client.attachments.AttachmentFailure
import com.deepseek.client.attachments.AttachmentInput
import com.deepseek.client.attachments.AttachmentPolicy
import com.deepseek.client.attachments.AttachmentStorage
import com.deepseek.client.attachments.ImagePreprocessor
import com.deepseek.client.attachments.UploadFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.nio.ByteBuffer

class AndroidAttachmentStorage(context: Context) : AttachmentStorage {
    private val directory = File(context.filesDir, "attachment-originals")

    private fun file(id: String): File {
        require(AttachmentPolicy.isLocalId(id))
        return File(directory, id)
    }

    override suspend fun read(id: String, limit: Int): ByteArray? = withContext(Dispatchers.IO) {
        try {
            AtomicFile(file(id)).openRead().use { stream ->
                val length = stream.channel.size()
                if (length !in 1..limit.toLong()) throw AttachmentFailure("无法读取本地附件")
                ByteArray(length.toInt()).also { bytes ->
                    java.io.DataInputStream(stream).readFully(bytes)
                    check(stream.read() == -1)
                }
            }
        } catch (_: FileNotFoundException) {
            null
        }
    }

    override suspend fun write(id: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        check(directory.isDirectory || directory.mkdirs())
        val target = AtomicFile(file(id))
        val stream = target.startWrite()
        try {
            stream.write(bytes)
            target.finishWrite(stream)
        } catch (error: Exception) {
            target.failWrite(stream)
            throw error
        }
    }

    override suspend fun delete(ids: Set<String>) = withContext(Dispatchers.IO) {
        ids.forEach { id ->
            val original = file(id)
            AtomicFile(original).delete()
            check(!original.exists())
        }
    }

    companion object {
        fun previewUri(context: Context, id: String, name: String): Uri {
            val original = AndroidAttachmentStorage(context).file(id)
            check(original.isFile)
            return FileProvider.getUriForFile(context, "${context.packageName}.attachments", original, name)
        }
    }
}

suspend fun ContentResolver.attachmentInputs(uris: List<Uri>): List<AttachmentInput> = withContext(Dispatchers.IO) {
    uris.map { uri ->
        val name = query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: throw AttachmentFailure("无法读取附件文件名")
        AttachmentInput(name) { limit ->
            withContext(Dispatchers.IO) {
                openInputStream(uri)?.use { it.readBounded(limit) } ?: throw AttachmentFailure("无法打开所选附件")
            }
        }
    }
}

private fun InputStream.readBounded(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        if (count > limit - output.size()) throw AttachmentFailure("附件超过 ${limit / (1024 * 1024)} MiB")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

object AndroidImagePreprocessor : ImagePreprocessor {
    override suspend fun prepare(file: UploadFile): UploadFile = withContext(Dispatchers.Default) {
        // Probe dimensions before allocating a bitmap, including on older Android devices.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(file.bytes, 0, file.bytes.size, bounds)
        val fitted = AttachmentPolicy.fitImage(bounds.outWidth, bounds.outHeight)
        if (fitted.width == bounds.outWidth && fitted.height == bounds.outHeight) return@withContext file

        val bitmap = if (Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(ByteBuffer.wrap(file.bytes))
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val target = AttachmentPolicy.fitImage(info.size.width, info.size.height)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSize(target.width, target.height)
            }
        } else {
            var sample = 1
            val largest = maxOf(bounds.outWidth, bounds.outHeight).toLong()
            while (largest > sample.toLong() * AttachmentPolicy.MAX_IMAGE_EDGE) sample *= 2
            val sampled = BitmapFactory.decodeByteArray(file.bytes, 0, file.bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample }) ?: throw AttachmentFailure("无法打开图片")
            val scaled = try {
                Bitmap.createScaledBitmap(sampled, fitted.width, fitted.height, true)
            } catch (error: Exception) {
                sampled.recycle()
                throw error
            }
            if (scaled !== sampled) sampled.recycle()
            applyOrientation(scaled, file)
        }

        try {
            val mime = if (file.mediaType == "image/jpeg") "image/jpeg" else "image/png"
            val format = if (mime == "image/jpeg") Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG
            val output = ByteArrayOutputStream()
            if (!bitmap.compress(format, 95, output)) throw AttachmentFailure("无法编码缩小后的图片")
            UploadFile(AttachmentPolicy.resizedName(file.name, mime), mime, output.toByteArray())
        } finally {
            bitmap.recycle()
        }
    }

    private fun applyOrientation(bitmap: Bitmap, file: UploadFile): Bitmap {
        if (file.mediaType != "image/jpeg") return bitmap
        val orientation = runCatching {
            ExifInterface(file.bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return try {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also {
                if (it !== bitmap) bitmap.recycle()
            }
        } catch (error: Exception) {
            bitmap.recycle()
            throw error
        }
    }
}
