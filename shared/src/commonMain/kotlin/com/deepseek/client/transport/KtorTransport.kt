package com.deepseek.client.transport

import com.deepseek.client.attachments.UploadFile
import com.deepseek.client.protocol.SseDecoder
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.io.readByteArray
import kotlinx.coroutines.CancellationException

/** Same framing, limits and cancellation on OkHttp (Android/JVM) and Darwin (iOS). */
class KtorTransport : HttpTransport {
    private val client = HttpClient {
        followRedirects = false // Never forward a Key through an origin-changing redirect.
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 20_000
            socketTimeoutMillis = 180_000
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        }
    }

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse = guarded {
        client.prepareGet(url) { headers.forEach { (key, value) -> header(key, value) } }.execute { response ->
            HttpResponse(response.status.value, response.headers.entries().associate { it.key to it.value.joinToString(",") })
        }
    }

    override suspend fun postFile(url: String, headers: Map<String, String>, file: UploadFile, purpose: String?): HttpResponse = guarded {
        client.preparePost(url) {
            headers.forEach { (key, value) -> header(key, value) }
            setBody(MultiPartFormDataContent(formData {
                if (purpose != null) append("purpose", purpose)
                append("file", file.bytes, Headers.build {
                    append(HttpHeaders.ContentType, file.mediaType)
                    append(HttpHeaders.ContentDisposition, "filename=\"${file.name}\"")
                })
            }))
        }.execute { response ->
            HttpResponse(response.status.value, emptyMap(), response.smallBody())
        }
    }

    override suspend fun postSse(url: String, headers: Map<String, String>, body: String, onEvent: suspend (String, String) -> Boolean): HttpResponse = guarded {
        client.preparePost(url) {
            headers.forEach { (key, value) -> header(key, value) }
            setBody(body)
        }.execute { response ->
            val result = HttpResponse(response.status.value, response.headers.entries().associate { it.key to it.value.joinToString(",") })
            if (response.status.value !in 200..299) return@execute result.copy(
                body = if (response.status.value == 400) response.smallBody() else null,
            )
            val contentType = response.headers[HttpHeaders.ContentType].orEmpty().substringBefore(';').trim()
            if (!contentType.equals("text/event-stream", true)) throw HttpException(HttpException.Kind.Protocol, "服务未返回事件流")
            val channel = response.bodyAsChannel()
            val decoder = SseDecoder()
            // readUTF8Line uses Ktor's incremental UTF-8 decoder and applies a bounded line length.
            try {
                while (!channel.isClosedForRead) {
                    val line = channel.readUTF8Line(4 * 1024 * 1024) ?: break
                    for (event in decoder.push(line + "\n")) {
                        if (!onEvent(event.event, event.data)) return@execute result
                    }
                }
                for (event in decoder.end()) if (!onEvent(event.event, event.data)) break
            } finally {
                channel.cancel()
            }
            result
        }
    }

    private suspend fun io.ktor.client.statement.HttpResponse.smallBody(): String? {
        val channel = bodyAsChannel()
        return try {
            val bytes = channel.readRemaining((MAX_JSON_RESPONSE_BYTES + 1).toLong()).readByteArray()
            bytes.takeIf { it.size <= MAX_JSON_RESPONSE_BYTES }?.decodeToString()
        } finally {
            channel.cancel()
        }
    }

    private suspend fun <T> guarded(block: suspend () -> T): T = try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (known: HttpException) { throw known }
        catch (error: IllegalArgumentException) { throw HttpException(HttpException.Kind.Protocol, "事件流格式或长度异常") }
        catch (error: Exception) { throw HttpException(HttpException.Kind.Network, "网络连接失败，请稍后重试") }

    override fun close() = client.close()
}
