package com.deepseek.client.runtime

import com.deepseek.client.api.*
import com.deepseek.client.domain.*
import com.deepseek.client.mvi.*
import com.deepseek.client.storage.*
import com.deepseek.client.transport.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

class NetworkSliceTest {
    private class Keys(var value: String? = "fixture-key") : SecureKeyStore {
        var rejectWrites = false
        override suspend fun read() = value
        override suspend fun save(value: String) { check(!rejectWrites); this.value = value }
        override suspend fun clear() { value = null }
    }
    private class History(var value: String? = null) : SnapshotStore {
        var writes = 0
        override suspend fun read() = value
        override suspend fun write(value: String) { this.value = value; writes++ }
    }
    private val frames = listOf(
        """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
        """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你好🌊"}}""",
        """{"type":"message_delta","delta":{"stop_reason":"end_turn","title":"你好"}}""",
        """{"type":"message_stop"}"""
    )
    private fun stream(values: List<String> = frames) = MockResponse().setHeader("Content-Type", "text/event-stream; charset=utf-8")
        .setBody(values.joinToString("") { "data: $it\r\n\r\n" })

    @Test fun real_http_split_utf8_and_crlf_complete_one_message_and_reuse_history() = runBlocking {
        val server = MockWebServer(); server.start()
        val transport = KtorTransport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val history = History()
        val controller = ChatController(scope, Keys(), history, transport, server.url("/").toString().trimEnd('/'))
        try {
            server.enqueue(stream().throttleBody(1, 1, TimeUnit.MILLISECONDS))
            controller.initialize()
            controller.dispatch(ChatIntent.SendMessage(TurnId("first"), "hi"))
            val finished = withTimeout(10_000) { controller.state.first { it.sessions.isNotEmpty() && it.activeTurnId == null } }
            val reply = finished.sessions.single().messages.last()
            assertEquals(MessageStatus.Completed, reply.status)
            assertEquals("你好🌊", (reply.blocks.single() as ContentBlock.Text).text)
            val firstRequest = server.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("/anthropic/v1/messages", firstRequest.path)
            assertEquals("fixture-key", firstRequest.getHeader("x-api-key"))
            assertFalse(firstRequest.body.readUtf8().contains("fixture-key"))
            server.enqueue(stream())
            controller.dispatch(ChatIntent.SendMessage(TurnId("second"), "再说一句"))
            withTimeout(5000) { controller.state.first { it.sessions.single().messages.size == 4 && it.activeTurnId == null } }
            val body = Json.parseToJsonElement(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()).jsonObject
            assertEquals(3, body["messages"]!!.jsonArray.size)
            controller.flush()
            assertEquals(4, SnapshotCodec.decode(history.value!!).sessions.single().messages.size)
            assertFalse(history.value!!.contains("fixture-key"))
        } finally { controller.close(); scope.cancel(); server.shutdown() }
    }
    @Test fun eof_without_stop_is_failed_with_partial_text() = runBlocking {
        val server = MockWebServer(); server.start(); server.enqueue(stream(frames.take(2)))
        val transport = KtorTransport()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = ChatController(scope, Keys(), History(), transport, server.url("/").toString().trimEnd('/'))
        try {
            controller.initialize(); controller.dispatch(ChatIntent.SendMessage(TurnId("broken"), "hi"))
            val state = withTimeout(5000) { controller.state.first { it.sessions.isNotEmpty() && it.activeTurnId == null } }
            assertEquals(MessageStatus.Failed, state.sessions.single().messages.last().status)
            assertEquals("你好🌊", (state.sessions.single().messages.last().blocks.single() as ContentBlock.Text).text)
            assertTrue(state.sessions.single().messages.last().error!!.contains("中断"))
        } finally { controller.close(); scope.cancel(); server.shutdown() }
    }
    @Test fun cancel_returns_immediately_without_waiting_for_server_eof() = runBlocking {
        val server = MockWebServer(); server.start()
        server.enqueue(stream().setBodyDelay(10, TimeUnit.SECONDS))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = ChatController(scope, Keys(), History(), KtorTransport(), server.url("/").toString().trimEnd('/'))
        try {
            controller.initialize(); controller.dispatch(ChatIntent.SendMessage(TurnId("cancel"), "hi"))
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(3, TimeUnit.SECONDS) })
            withTimeout(1000) { controller.dispatch(ChatIntent.CancelTurn(TurnId("cancel"))) }
            assertNull(controller.state.value.activeTurnId)
            assertEquals(MessageStatus.Cancelled, controller.state.value.sessions.single().messages.last().status)
        } finally { controller.close(); scope.cancel(); server.shutdown() }
    }
    @Test fun redirect_is_not_followed_and_key_is_not_forwarded() = runBlocking {
        val from = MockWebServer(); val to = MockWebServer(); from.start(); to.start()
        from.enqueue(MockResponse().setResponseCode(307).setHeader("Location", to.url("/stolen")))
        val transport = KtorTransport()
        try {
            val api = DeepSeekApi(transport, from.url("/").toString().trimEnd('/'))
            val error = assertFailsWith<HttpException> { api.verifyApiKey("fixture-key") }
            assertEquals(307, error.status)
            assertEquals(0, to.requestCount)
        } finally { transport.close(); from.shutdown(); to.shutdown() }
    }
    @Test fun successful_key_check_does_not_claim_success_when_secure_write_fails() = runTest {
        val keys = Keys(null).apply { rejectWrites = true }
        val transport = object : HttpTransport {
            override suspend fun get(url: String, headers: Map<String, String>) = HttpResponse(200, emptyMap())
            override suspend fun postSse(url: String, headers: Map<String, String>, body: String, onEvent: suspend (String, String) -> Boolean) = error("unexpected")
        }
        val controller = ChatController(backgroundScope, keys, History(), transport)
        controller.initialize(); controller.validateAndSaveKey("fixture-key")
        testScheduler.runCurrent()
        assertFalse(controller.state.value.apiKeyConfigured)
        assertFalse(controller.state.value.keyChecking)
        assertNotNull(controller.state.value.keyError)
        assertNull(keys.value)
        controller.close()
    }
    @Test fun corrupt_history_is_preserved_and_never_replaced_by_empty_snapshot() = runTest {
        val history = History("damaged")
        val transport = object : HttpTransport {
            override suspend fun get(url: String, headers: Map<String, String>) = error("unexpected")
            override suspend fun postSse(url: String, headers: Map<String, String>, body: String, onEvent: suspend (String, String) -> Boolean) = error("unexpected")
        }
        val controller = ChatController(backgroundScope, Keys(), history, transport)
        controller.initialize(); controller.flush()
        assertTrue(controller.state.value.ready)
        assertNotNull(controller.state.value.storageError)
        assertEquals(0, history.writes)
        assertEquals("damaged", history.value)
        controller.close()
    }
}
