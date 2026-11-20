package com.deepseek.client.attachments

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

@Serializable
data class Attachment(
    val id: String,
    val name: String,
    val sizeBytes: Int,
    val mediaType: String,
    val serverFileId: String? = null,
    val imagePolicyVersion: Int = 0,
) {
    val isImage get() = mediaType in AttachmentPolicy.imageTypes
    val purpose: String? get() = when {
        isImage -> null
        AttachmentPolicy.extension(name) in AttachmentPolicy.layoutTypes -> "rich_layout_recognition"
        else -> "user_data"
    }
}

/** The conversation snapshot stores Attachment metadata. Only the platform vault holds bytes. */
interface AttachmentStorage {
    suspend fun read(id: String, limit: Int): ByteArray?
    suspend fun write(id: String, bytes: ByteArray)
    suspend fun delete(ids: Set<String>)
}

data class UploadFile(val name: String, val mediaType: String, val bytes: ByteArray)
data class ImageSize(val width: Int, val height: Int)
data class AttachmentInput(val name: String, val read: suspend (limit: Int) -> ByteArray)

/** Return the original object when its dimensions already meet the policy. */
fun interface ImagePreprocessor {
    suspend fun prepare(file: UploadFile): UploadFile
}

class AttachmentFailure(message: String) : Exception(message)

object AttachmentPolicy {
    const val MAX_IMAGE_EDGE = 4096
    const val IMAGE_POLICY_VERSION = 1
    const val MAX_PER_DRAFT = 50
    const val MAX_DOCUMENTS_IN_REQUEST = 20
    const val MAX_BYTES = 64 * 1024 * 1024
    private const val MAX_USER_DATA_BYTES = 32 * 1024 * 1024
    private val imageExtensions = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
        "gif" to "image/gif", "webp" to "image/webp", "bmp" to "image/bmp",
        "dib" to "image/bmp", "tif" to "image/tiff", "tiff" to "image/tiff",
        "avif" to "image/avif", "avifs" to "image/avif", "heic" to "image/heic",
        "heif" to "image/heif", "svg" to "image/svg+xml", "svgz" to "image/svg+xml",
        "ico" to "image/x-icon", "cur" to "image/x-icon", "apng" to "image/apng",
        "jfif" to "image/jpeg", "jpe" to "image/jpeg", "pjp" to "image/jpeg", "pjpeg" to "image/jpeg",
    )
    internal val imageTypes = imageExtensions.values.toSet()
    private val resizableImageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "tif", "tiff", "avif", "heic", "heif", "jfif", "jpe", "pjp", "pjpeg")
    private val checkableImageTypes = setOf(
        "image/jpeg", "image/png", "image/apng", "image/gif", "image/webp", "image/bmp",
        "image/tiff", "image/x-icon", "image/avif", "image/heic", "image/heif", "image/svg+xml",
    )
    internal val layoutTypes = mapOf(
        "pdf" to "application/pdf", "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    )
    private val knownDataTypes = mapOf(
        "txt" to "text/plain", "md" to "text/markdown", "csv" to "text/csv", "tsv" to "text/tab-separated-values",
        "json" to "application/json", "yaml" to "text/plain", "yml" to "text/plain", "log" to "text/plain",
        "py" to "text/plain", "js" to "text/plain", "ts" to "text/plain", "kt" to "text/plain", "swift" to "text/plain",
        "java" to "text/plain", "c" to "text/plain", "cpp" to "text/plain", "rs" to "text/plain", "go" to "text/plain",
        "xls" to "application/vnd.ms-excel", "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "epub" to "application/epub+zip", "mobi" to "application/x-mobipocket-ebook",
    )
    private val dataTypes = (OfficialUploadExtensions.all - imageExtensions.keys - layoutTypes.keys)
        .associateWith { knownDataTypes[it] ?: "application/octet-stream" }
    private val mediaTypes = imageExtensions + layoutTypes + dataTypes
    val acceptedExtensions: String get() = mediaTypes.keys.joinToString(",") { ".$it" }

    private val localIdPattern = Regex("attachment-[A-Za-z0-9-]{1,90}")
    internal val serverIdPattern = Regex("file-api-[A-Za-z0-9_-]{1,200}")

    fun extension(name: String) = name.substringAfterLast('.', "").lowercase()
    fun isLocalId(id: String): Boolean = localIdPattern.matches(id)
    internal fun canNormalizeImage(name: String) = extension(name) in resizableImageExtensions
    fun normalizeName(name: String): String {
        val base = name.substringAfterLast('/').substringAfterLast('\\')
            .filter { !it.isISOControl() && it != '"' }.trim()
        val ext = extension(base)
        if (ext !in mediaTypes) throw AttachmentFailure("此文件格式暂不支持")
        return "${base.substringBeforeLast('.').take(110).ifBlank { "attachment" }}.$ext"
    }

    fun maxBytes(name: String): Int = if (extension(name) in dataTypes) MAX_USER_DATA_BYTES else MAX_BYTES

    fun describe(id: String, rawName: String, bytes: ByteArray): Attachment {
        val name = normalizeName(rawName)
        val type = mediaTypes.getValue(extension(name))
        if (bytes.isEmpty()) throw AttachmentFailure("附件内容为空")
        if (bytes.size > maxBytes(name)) throw AttachmentFailure("附件超过 ${maxBytes(name) / (1024 * 1024)} MiB")
        if (type in checkableImageTypes) {
            val detected = imageType(bytes)
            if (detected == null || !sameImageFamily(type, detected)) throw AttachmentFailure("图片内容与文件格式不符")
        }
        return Attachment(id, name, bytes.size, type)
    }

    fun fitImage(width: Int, height: Int): ImageSize {
        if (width <= 0 || height <= 0) throw AttachmentFailure("无法读取图片尺寸")
        val longest = maxOf(width, height)
        if (longest <= MAX_IMAGE_EDGE) return ImageSize(width, height)
        val scale = MAX_IMAGE_EDGE.toDouble() / longest
        return ImageSize((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
    }

    fun resizedName(name: String, mediaType: String): String =
        name.substringBeforeLast('.') + when (mediaType) { "image/jpeg" -> ".jpg"; "image/webp" -> ".webp"; else -> ".png" }

    private fun imageType(bytes: ByteArray): String? {
        fun text(offset: Int, value: String) = bytes.size >= offset + value.length &&
            value.indices.all { bytes[offset + it] == value[it].code.toByte() }
        return when {
            bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg"
            bytes.firstOrNull() == 0x89.toByte() && text(1, "PNG\r\n\u001a\n") -> "image/png"
            text(0, "GIF87a") || text(0, "GIF89a") -> "image/gif"
            text(0, "RIFF") && text(8, "WEBP") -> "image/webp"
            text(0, "BM") -> "image/bmp"
            text(0, "II*\u0000") || text(0, "MM\u0000*") -> "image/tiff"
            bytes.size >= 4 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() &&
                bytes[2] in byteArrayOf(1, 2) && bytes[3] == 0.toByte() -> "image/x-icon"
            text(4, "ftyp") && (text(8, "avif") || text(8, "avis")) -> "image/avif"
            text(4, "ftyp") && (text(8, "heic") || text(8, "heix") || text(8, "hevc")) -> "image/heic"
            text(4, "ftyp") && text(8, "mif1") -> "image/heif"
            text(0, "<svg") || (text(0, "<?xml") && bytes.copyOfRange(0, minOf(bytes.size, 512)).decodeToString().contains("<svg", ignoreCase = true)) -> "image/svg+xml"
            else -> null
        }
    }

    private fun sameImageFamily(expected: String, detected: String) = expected == detected ||
        expected in setOf("image/jpeg") && detected == "image/jpeg" ||
        expected in setOf("image/png", "image/apng") && detected == "image/png" ||
        expected in setOf("image/heic", "image/heif") && detected in setOf("image/heic", "image/heif")
}
