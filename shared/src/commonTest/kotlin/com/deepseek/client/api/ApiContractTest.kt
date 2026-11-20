package com.deepseek.client.api

import com.deepseek.client.domain.TurnId
import com.deepseek.client.mvi.ChatAction
import com.deepseek.client.protocol.CreateMessageRequest
import com.deepseek.client.transport.HttpResponse
import com.deepseek.client.transport.HttpTransport
import com.deepseek.client.transport.HttpException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class ApiContractTest {
    private class FakeTransport(private val frames: List<String>) : HttpTransport {
        var body = ""
        override suspend fun get(url: String, headers: Map<String, String>) = HttpResponse(200, emptyMap())
        override suspend fun postSse(url: String, headers: Map<String, String>, body: String, onEvent: suspend (String, String) -> Boolean): HttpResponse {
            this.body = body
            frames.forEach { onEvent("message", it) }
            return HttpResponse(200, mapOf("Content-Type" to "text/event-stream"))
        }
    }

    @Test
    fun serializes_roles_and_named_server_tools() = runTest {
        val transport = FakeTransport(listOf("""{"type":"message_stop"}"""))
        val api = DeepSeekApi(transport)
        api.streamMessage("test-placeholder", api.buildRequest("你好\n\"world\"", true, true)) { _, _ -> }
        val root = Json.parseToJsonElement(transport.body).jsonObject
        assertEquals("user", root["messages"]!!.jsonArray.first().jsonObject["role"]!!.jsonPrimitive.content)
        val textBlock = root["messages"]!!.jsonArray.first().jsonObject["content"]!!.jsonArray.first().jsonObject
        assertEquals("text", textBlock["type"]!!.jsonPrimitive.content)
        assertEquals("你好\n\"world\"", textBlock["text"]!!.jsonPrimitive.content)
        root["tools"]!!.jsonArray.forEach { tool ->
            assertEquals(tool.jsonObject["type"], tool.jsonObject["name"])
        }
        assertFalse(transport.body.contains("test-placeholder"))
    }

    @Test
    fun missing_terminal_event_is_interrupted() = runTest {
        val api = DeepSeekApi(FakeTransport(listOf("""{"type":"ping"}""")))
        val error = assertFailsWith<HttpException> {
            api.streamMessage("test-placeholder", api.buildRequest("hi", false, false)) { _, _ -> }
        }
        assertEquals(HttpException.Kind.Interrupted, error.kind)
    }

    @Test
    fun unnamed_error_frame_is_stream_error() = runTest {
        val api = DeepSeekApi(FakeTransport(listOf("""{"error":{"message":"failed"}}""")))
        val error = assertFailsWith<HttpException> {
            api.streamMessage("test-placeholder", api.buildRequest("hi", false, false)) { _, _ -> }
        }
        assertEquals(HttpException.Kind.Stream, error.kind)
    }

    @Test
    fun mapper_decodes_escaped_strings_and_only_finishes_on_stop() {
        val mapper = StreamEventMapper()
        val turn = TurnId("a")
        mapper.actions(Json.parseToJsonElement("""{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""").jsonObject, turn)
        val text = mapper.map(Json.parseToJsonElement("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"你好\n\"ok\""}}""").jsonObject, turn) as ChatAction.TextDelta
        assertEquals("你好\n\"ok\"", text.delta)
        val metadata = mapper.map(Json.parseToJsonElement("""{"type":"message_delta","delta":{"stop_reason":"end_turn","title":"标题"}}""").jsonObject, turn)
        assertIs<ChatAction.ReplyMetadata>(metadata)
        assertIs<ChatAction.TurnCompleted>(mapper.map(Json.parseToJsonElement("""{"type":"message_stop"}""").jsonObject, turn))
    }
    @Test
    fun duplicate_start_does_not_reset_raw_history_after_text_arrives() {
        val mapper = StreamEventMapper()
        val turn = TurnId("duplicate")
        fun emit(text: String) = mapper.actions(Json.parseToJsonElement(text).jsonObject, turn)
        val start = """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}"""
        emit(start)
        emit("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"前半"}}""")
        assertTrue(emit(start).isEmpty())
        val raw = emit("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"后半"}}""")
            .filterIsInstance<ChatAction.WireBlock>().single()
        assertEquals("前半后半", raw.value["text"]!!.jsonPrimitive.content)
    }

}
