package com.deepseek.client.transport

import com.deepseek.client.attachments.UploadFile

interface HttpTransport {
    suspend fun get(url: String, headers: Map<String, String>): HttpResponse
    suspend fun postFile(url: String, headers: Map<String, String>, file: UploadFile, purpose: String?): HttpResponse =
        throw HttpException(HttpException.Kind.Protocol, "当前平台无法上传附件")
    /** Return false from the consumer to close the response immediately (message_stop or error). */
    suspend fun postSse(url: String, headers: Map<String, String>, body: String, onEvent: suspend (String, String) -> Boolean): HttpResponse
    fun close() {}
}

/** Small JSON bodies used internally for Files results and known HTTP errors. */
data class HttpResponse(val status: Int, val headers: Map<String, String>, val body: String? = null)

internal const val MAX_JSON_RESPONSE_BYTES = 32 * 1024

class HttpException(
    val kind: Kind, message: String, val status: Int? = null,
    internal val invalidFileIds: Set<String>? = null,
) : Exception(message) {
    enum class Kind { Http, Stream, Network, Interrupted, Protocol }
}
