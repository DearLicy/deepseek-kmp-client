@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package com.deepseek.client.bridge

import com.deepseek.client.attachments.*
import com.deepseek.client.domain.MessageId
import com.deepseek.client.domain.BlockId
import com.deepseek.client.domain.SessionId
import com.deepseek.client.mvi.ChatIntent
import com.deepseek.client.runtime.ChatController
import com.deepseek.client.storage.SecureKeyStore
import com.deepseek.client.storage.SnapshotStore
import com.deepseek.client.transport.MAX_JSON_RESPONSE_BYTES
import com.deepseek.client.transport.HttpException
import com.deepseek.client.transport.HttpResponse
import com.deepseek.client.transport.HttpTransport
import com.deepseek.client.protocol.SseDecoder
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.js.Promise
import kotlinx.coroutines.*
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.collect

/** Browser-specific façade; Web remains on the exact same reducer and chat controller as native. */
@JsExport
class BrowserClient(private val files: dynamic) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val keys = SessionKeyStore()
    private val snapshots = BrowserSnapshotStore()
    private val transport = BrowserFetchTransport()
    private val controller = ChatController(scope, keys, snapshots, transport,
        attachmentStorage = object : AttachmentStorage {
            override suspend fun read(id: String, limit: Int): ByteArray? =
                (files.read(id, limit) as Promise<ByteArray?>).await()
            override suspend fun write(id: String, bytes: ByteArray) {
                (files.write(id, bytes) as Promise<dynamic>).await()
            }
            override suspend fun delete(ids: Set<String>) {
                (files.remove(ids.toTypedArray()) as Promise<dynamic>).await()
            }
        }, imagePreprocessor = ImagePreprocessor { file ->
            val result = (files.resize(file.name, file.mediaType, file.bytes, AttachmentPolicy.MAX_IMAGE_EDGE) as Promise<dynamic>).await()
            if (result == null) file else UploadFile(result.name as String, result.type as String, result.bytes as ByteArray)
        })
    private var observer: Job? = null

    fun start(onState: (String) -> Unit) {
        observer?.cancel()
        observer = scope.launch { controller.state.collect { onState(NativeProjection.encode(it)) } }
        scope.launch { controller.initialize() }
    }
    fun acceptedFiles(): String = AttachmentPolicy.acceptedExtensions
    fun addFiles(values: Array<dynamic>) {
        scope.launch {
            controller.stageAttachments(values.map { file ->
                AttachmentInput(file.name as String) { limit ->
                    if ((file.size as Double) > limit) throw AttachmentFailure("附件超过 ${limit / (1024 * 1024)} MiB")
                    (files.bytes(file) as Promise<ByteArray>).await()
                }
            })
        }
    }
    fun removeAttachment(id: String) { controller.submit(ChatIntent.RemoveAttachment(id)) }
    fun editForResend(id: String) { controller.submit(ChatIntent.EditForResend(MessageId(id))) }
    fun localFile(id: String): Promise<dynamic> = scope.promise {
        controller.readAttachment(id)?.let { file -> files.blob(file.bytes, file.mediaType) }
    }
    fun input(text: String) { controller.submit(ChatIntent.InputChanged(text)) }
    fun send() { controller.send() }
    fun stop() { controller.stop() }
    fun newSession() { controller.submit(ChatIntent.NewSession) }
    fun selectSession(id: String) { controller.submit(ChatIntent.SelectSession(SessionId(id))) }
    fun renameSession(id: String, title: String) { controller.submit(ChatIntent.RenameSession(SessionId(id), title)) }
    fun pinSession(id: String) { controller.submit(ChatIntent.PinSession(SessionId(id))) }
    fun deleteSession(id: String) { controller.submit(ChatIntent.DeleteSession(SessionId(id))) }
    fun deleteAll() { controller.submit(ChatIntent.DeleteAll) }
    fun toggleThinking() { controller.submit(ChatIntent.ToggleThinking) }
    fun toggleSearch() { controller.submit(ChatIntent.ToggleSearch) }
    fun toggleThinkingBlock(id: String) { controller.submit(ChatIntent.ToggleThinkingBlock(BlockId(id))) }
    fun setTheme(value: String) { controller.submit(ChatIntent.SetTheme(value)) }
    fun setFontScale(value: Double) { controller.submit(ChatIntent.SetFontScale(value.toFloat())) }
    fun validateAndSaveKey(value: String) { controller.validateAndSaveKey(value) }
    fun clearKey() { controller.clearKey() }
    fun dismissNotice() { controller.submit(ChatIntent.DismissMessage) }
    fun showNotice(message: String) { controller.submit(ChatIntent.ShowMessage(message)) }
    fun flush() { scope.launch { controller.flush() } }
    fun close() { observer?.cancel(); controller.close(); scope.cancel() }
}

private class SessionKeyStore : SecureKeyStore {
    private val storage: dynamic = js("globalThis.sessionStorage")
    override suspend fun read(): String? = try { storage.getItem("deepseek.api-key.v1") as? String } catch (_: Throwable) { throw HttpException(HttpException.Kind.Http, "浏览器拒绝访问会话存储") }
    override suspend fun save(value: String) { try { storage.setItem("deepseek.api-key.v1", value) } catch (_: Throwable) { throw HttpException(HttpException.Kind.Http, "API Key 无法写入本地会话存储") } }
    override suspend fun clear() { try { storage.removeItem("deepseek.api-key.v1") } catch (_: Throwable) { throw HttpException(HttpException.Kind.Http, "API Key 无法从本地会话存储移除") } }
}

private class BrowserSnapshotStore : SnapshotStore {
    private val storage: dynamic = js("globalThis.localStorage")
    override suspend fun read(): String? = try { storage.getItem("deepseek-client.snapshot.v1") as? String } catch (_: Throwable) { throw IllegalStateException("浏览器历史存储不可用") }
    override suspend fun write(value: String) { try { storage.setItem("deepseek-client.snapshot.v1", value) } catch (_: Throwable) { throw IllegalStateException("浏览器存储空间不足") } }
}

/** Direct same-origin-safe Fetch with real ReadableStream SSE and redirect refusal. */
private class BrowserFetchTransport : HttpTransport {
    private val fetcher: dynamic = js("globalThis.fetch.bind(globalThis)")

    private suspend fun request(url: String, method: String, headers: Map<String, String>, body: dynamic = null, signal: dynamic = null): dynamic {
        val options: dynamic = js("({})")
        options.method = method
        val requestHeaders: dynamic = js("new Headers()")
        headers.forEach { (key, value) -> requestHeaders.set(key, value) }
        options.headers = requestHeaders
        options.credentials = "omit"
        options.referrerPolicy = "no-referrer"
        options.signal = signal
        options.redirect = "error"
        if (body != null) options.body = body
        return (fetcher(url, options) as Promise<dynamic>).await()
    }
    private fun responseHeaders(response: dynamic): Map<String, String> {
        val result = mutableMapOf<String, String>()
        response.headers.forEach({ value: String, name: String -> result[name.lowercase()] = value })
        return result
    }
    override suspend fun get(url: String, headers: Map<String, String>): HttpResponse = try {
        val response = request(url, "GET", headers)
        HttpResponse(response.status as Int, responseHeaders(response))
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Throwable) { throw HttpException(HttpException.Kind.Network, "网络连接失败，请稍后重试") }

    override suspend fun postFile(url: String, headers: Map<String, String>, file: UploadFile, purpose: String?): HttpResponse = guarded {
        val abort: dynamic = js("new AbortController()")
        try {
            val form: dynamic = js("new FormData()")
            if (purpose != null) form.append("purpose", purpose)
            val bytes = file.bytes
            val mime = file.mediaType
            val blob: dynamic = js("new Blob([bytes], {type: mime})")
            form.append("file", blob, file.name)
            val response = request(url, "POST", headers, form, abort.signal)
            HttpResponse(response.status as Int, emptyMap(), readSmallBody(response))
        } finally { abort.abort() }
    }

    private suspend fun readSmallBody(response: dynamic): String? {
        val reader: dynamic = response.body?.getReader() ?: return null
        val decoder: dynamic = js("new TextDecoder()")
        val text = StringBuilder()
        var size = 0
        try {
            while (true) {
                val item = (reader.read() as Promise<dynamic>).await()
                if (item.done as Boolean) break
                val chunkSize = item.value.byteLength as Int
                if (chunkSize > MAX_JSON_RESPONSE_BYTES - size) return null
                size += chunkSize
                text.append(decoder.decode(item.value, js("({stream:true})")) as String)
            }
            return text.append(decoder.decode() as String).toString()
        } finally { reader.cancel(); reader.releaseLock() }
    }

    private suspend fun <T> guarded(block: suspend () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (known: HttpException) { throw known }
    catch (_: Exception) { throw HttpException(HttpException.Kind.Network, "网络连接失败，请稍后重试") }

    override suspend fun postSse(url: String, headers: Map<String, String>, body: String, onEvent: suspend (String, String) -> Boolean): HttpResponse {
        val abort: dynamic = js("new AbortController()")
        var reader: dynamic = null
        try {
            val response = request(url, "POST", headers, body, abort.signal)
            val result = HttpResponse(response.status as Int, responseHeaders(response))
            if (result.status !in 200..299) return result.copy(body = if (result.status == 400) readSmallBody(response) else null)
            if (result.headers["content-type"].orEmpty().substringBefore(';').trim().lowercase() != "text/event-stream") {
                throw HttpException(HttpException.Kind.Protocol, "服务未返回事件流")
            }
            if (response.body == null) throw HttpException(HttpException.Kind.Protocol, "服务未返回事件流")
            reader = response.body.getReader()
            val textDecoder: dynamic = js("new TextDecoder('utf-8')")
            val decoder = SseDecoder()
            while (true) {
                val item: dynamic = (reader.read() as Promise<dynamic>).await()
                if (item.done as Boolean) break
                val text = textDecoder.decode(item.value, js("({stream:true})")) as String
                for (event in decoder.push(text)) if (!onEvent(event.event, event.data)) {
                    reader.cancel()
                    return result
                }
            }
            for (event in decoder.end()) if (!onEvent(event.event, event.data)) break
            return result
        } catch (cancelled: CancellationException) {
            try { reader?.cancel() } catch (_: Throwable) { }
            throw cancelled
        } catch (known: HttpException) { throw known }
        catch (_: Throwable) { throw HttpException(HttpException.Kind.Network, "网络连接失败，请稍后重试") }
        finally { abort.abort(); try { reader?.releaseLock() } catch (_: Throwable) { } }
    }
}
