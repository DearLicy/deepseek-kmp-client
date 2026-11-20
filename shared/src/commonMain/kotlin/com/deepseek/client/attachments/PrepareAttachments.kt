package com.deepseek.client.attachments

import com.deepseek.client.api.DeepSeekApi
import com.deepseek.client.api.RequestBuilder
import com.deepseek.client.domain.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal class PrepareAttachments(
    private val storage: AttachmentStorage?,
    private val images: ImagePreprocessor?,
    private val api: DeepSeekApi,
) {
    data class Result(val history: List<Message>, val current: List<Attachment>, val skipped: Int)

    suspend fun forTurn(
        key: String,
        history: List<Message>,
        question: Message,
        updated: suspend (Attachment) -> Unit,
    ): Result {
        val prepared = mutableMapOf<String, Attachment?>()
        var documentSlots = AttachmentPolicy.MAX_DOCUMENTS_IN_REQUEST
        var skipped = 0

        suspend fun prepare(file: Attachment, required: Boolean): Attachment? {
            if (!file.isImage && documentSlots == 0) {
                if (required) throw AttachmentFailure("本轮文档超过数量上限")
                skipped++
                return null
            }
            if (file.id !in prepared) {
                prepared[file.id] = try {
                    ensure(key, file, updated)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (required) {
                        if (error is AttachmentFailure) throw error
                        throw AttachmentFailure("附件「${file.name}」上传失败。请编辑后重发")
                    }
                    null
                }
            }
            val result = prepared[file.id]
            if (result == null) skipped++
            else if (!file.isImage) documentSlots--
            return result
        }

        val current = question.attachments.mapNotNull { prepare(it, required = true) }
        val questions = RequestBuilder.contextRounds(history).map { (user, _) -> user }.asReversed()
        val filesByMessage = questions.associate { message ->
            message.id to message.attachments.mapNotNull { prepare(it, required = false) }
        }
        val context = history.map { message ->
            filesByMessage[message.id]?.let { message.copy(attachments = it) } ?: message
        }
        return Result(context, current, skipped)
    }

    private suspend fun ensure(key: String, file: Attachment, updated: suspend (Attachment) -> Unit): Attachment {
        coroutineContext.ensureActive()
        val existingId = file.serverFileId
        if (existingId != null && (!file.isImage || file.imagePolicyVersion == AttachmentPolicy.IMAGE_POLICY_VERSION)) return file

        val original = try {
            storage?.read(file.id, AttachmentPolicy.maxBytes(file.name))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (original == null) {
            if (existingId != null) return file
            throw AttachmentFailure("找不到附件「${file.name}」的本地原件，请重新选择")
        }
        val input = UploadFile(file.name, file.mediaType, original)
        val upload = if (file.isImage && AttachmentPolicy.canNormalizeImage(file.name)) {
            val processor = images ?: throw AttachmentFailure("当前平台无法处理图片")
            try {
                processor.prepare(input)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (existingId != null) return file
                throw AttachmentFailure("无法处理图片「${file.name}」，请重新选择")
            }
        } else input
        if (existingId != null && upload === input) {
            return file.copy(imagePolicyVersion = AttachmentPolicy.IMAGE_POLICY_VERSION).also { updated(it) }
        }
        if (existingId != null) updated(file.copy(serverFileId = null, imagePolicyVersion = 0))
        val checked = AttachmentPolicy.describe(file.id, upload.name, upload.bytes)
        if (checked.mediaType != upload.mediaType) throw AttachmentFailure("待上传附件的格式与文件名不符")
        coroutineContext.ensureActive()
        val serverId = api.uploadFile(key, upload, file.purpose)
        coroutineContext.ensureActive()
        return file.copy(
            serverFileId = serverId,
            imagePolicyVersion = if (file.isImage) AttachmentPolicy.IMAGE_POLICY_VERSION else 0,
        ).also { updated(it) }
    }
}
