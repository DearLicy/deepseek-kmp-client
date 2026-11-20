package com.deepseek.client.api

import com.deepseek.client.attachments.AttachmentFailure
import com.deepseek.client.attachments.AttachmentPolicy
import com.deepseek.client.attachments.UploadFile
import com.deepseek.client.protocol.CreateMessageRequest
import com.deepseek.client.protocol.MessageParam
import com.deepseek.client.protocol.UserContentBlock
import com.deepseek.client.transport.HttpException
import com.deepseek.client.transport.HttpTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.*

class DeepSeekApi(
    private val transport: HttpTransport,
    private val apiOrigin: String = "https://api.deepseek.com",
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false },
) {
    private companion object {
        const val FILES_BETA = "files-api-2025-04-14"
        val invalidFileError = Regex(
            "file_ids do not exist or are not created under your account|file data not found for file_id|\\.source\\.file_id: invalid file_id",
            RegexOption.IGNORE_CASE,
        )
    }

    suspend fun uploadFile(apiKey: String, file: UploadFile, purpose: String?): String {
        val response = transport.postFile(
            url = "$apiOrigin/anthropic/v1/files",
            headers = mapOf("x-api-key" to apiKey, "anthropic-beta" to FILES_BETA),
            file = file,
            purpose = purpose,
        )
        if (response.status !in 200..299) throw AttachmentFailure(when (response.status) {
            400 -> "服务端无法解析附件或不支持该格式"
            401, 403 -> "API Key 无效或没有附件权限"
            402 -> "余额不足，附件上传失败"
            413 -> "附件超过服务端大小限制"
            429 -> "上传过于频繁或达到配额，请稍后重试"
            else -> "附件上传失败（HTTP ${response.status}）"
        })
        return (parseObject(response.body)?.get("id") as? JsonPrimitive)?.contentOrNull
            ?.takeIf(AttachmentPolicy.serverIdPattern::matches)
            ?: throw AttachmentFailure("服务端没有返回有效的附件编号")
    }

    suspend fun verifyApiKey(apiKey: String) {
        val response = transport.get(
            url = "$apiOrigin/models",
            headers = mapOf("Authorization" to "Bearer $apiKey"),
        )
        if (response.status !in 200..299) {
            throw HttpException(
                kind = HttpException.Kind.Http,
                message = "API Key 校验失败（HTTP ${response.status}）",
                status = response.status,
            )
        }
    }

    suspend fun streamMessage(
        apiKey: String,
        request: CreateMessageRequest,
        onEvent: suspend (event: String, payload: JsonObject) -> Unit,
    ) {
        var sawMessageStop = false
        val body = json.encodeToString(CreateMessageRequest.serializer(), request)
        val headers = buildMap {
            put("x-api-key", apiKey)
            put("Content-Type", "application/json")
            put("Accept", "text/event-stream")
            if (request.messages.filterIsInstance<MessageParam.User>().any { message ->
                message.content.any { it is UserContentBlock.Image || it is UserContentBlock.Document }
            }) put("anthropic-beta", FILES_BETA)
        }
        val response = transport.postSse(
            url = "$apiOrigin/anthropic/v1/messages",
            headers = headers,
            body = body,
        ) { event, data ->
            val payload = parseObject(data)
            if (event == "error" || payload?.get("error") is JsonObject) {
                // Do not surface arbitrary upstream text that could echo request secrets.
                throw HttpException(HttpException.Kind.Stream, "服务端返回生成错误，请稍后重试")
            }
            if (payload != null) {
                onEvent(event, payload)
                sawMessageStop = (payload["type"] as? JsonPrimitive)?.contentOrNull == "message_stop"
            }
            !sawMessageStop
        }
        if (response.status == 400) {
            val error = parseObject(response.body)?.get("error") as? JsonObject
            val message = (error?.get("message") as? JsonPrimitive)?.contentOrNull
            if (message != null && invalidFileError.containsMatchIn(message)) {
                val ids = AttachmentPolicy.serverIdPattern.findAll(message).map { it.value }.toSet()
                throw HttpException(HttpException.Kind.Http, "附件引用失效，请重新上传", 400, ids)
            }
        }
        if (response.status !in 200..299) throw HttpException(HttpException.Kind.Http, when (response.status) {
            401, 403 -> "API Key 无效或没有该能力权限"
            429 -> "请求频繁或额度不足，请稍后重试"
            else -> "聊天请求失败（HTTP ${response.status}）"
        }, response.status)
        if (!sawMessageStop) throw HttpException(HttpException.Kind.Interrupted, "连接在消息结束前中断")
    }

    fun buildRequest(text: String, thinkingEnabled: Boolean, searchEnabled: Boolean): CreateMessageRequest =
        RequestBuilder.build(emptyList(), text, thinkingEnabled, searchEnabled)

    fun readError(exception: Throwable): String = when (exception) {
        is HttpException -> exception.message ?: "请求失败"
        is AttachmentFailure -> exception.message ?: "附件处理失败"
        else -> "请求失败，请稍后重试"
    }

    private fun parseObject(value: String?): JsonObject? = value?.let {
        runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull()
    }
}
