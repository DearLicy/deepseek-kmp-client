package com.deepseek.client.api

import com.deepseek.client.domain.*
import com.deepseek.client.protocol.CreateMessageRequest
import kotlinx.serialization.json.*
import kotlin.test.*

class RequestBuilderTest {
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private fun user(id: String) = Message(MessageId(id), MessageRole.User, 1, MessageStatus.Completed, listOf(ContentBlock.Text(BlockId(id), 0, "问题")))
    private fun assistant(status: MessageStatus) = Message(MessageId("a"), MessageRole.Assistant, 2, status,
        listOf(ContentBlock.Thinking(BlockId("think"), 0, "私有推理"), ContentBlock.Text(BlockId("answer"), 1, "答案")))
    @Test fun complete_rounds_only_and_no_historical_thinking() {
        val history = listOf(user("1"), assistant(MessageStatus.Streaming), user("2"), assistant(MessageStatus.Completed))
        val wire = json.encodeToString(CreateMessageRequest.serializer(), RequestBuilder.build(history, "追问", false, false))
        val messages = Json.parseToJsonElement(wire).jsonObject["messages"]!!.jsonArray
        assertEquals(3, messages.size)
        assertFalse(wire.contains("私有推理"))
        assertEquals("追问", messages.last().jsonObject["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content)
    }
    @Test fun failed_partial_reply_and_refusal_are_not_replayed_as_context() {
        val history = listOf(user("1"), assistant(MessageStatus.Failed), user("2"), assistant(MessageStatus.Completed).copy(stopReason = "refusal"))
        val request = json.encodeToString(CreateMessageRequest.serializer(), RequestBuilder.build(history, "新的提问", false, false))
        assertEquals(1, Json.parseToJsonElement(request).jsonObject["messages"]!!.jsonArray.size)
    }
    @Test fun signed_read_link_result_and_matching_tool_are_retained() {
        val raw = listOf(
            """{"type":"server_tool_use","id":"tool1","name":"read_url","kind":"read_link","input":{"url":"https://example.com"}}""",
            """{"type":"web_fetch_tool_result","tool_use_id":"tool1","kind":"read_link","signature":"signed-value","content":{"type":"web_fetch_result","url":"https://example.com","content":{"type":"text","title":"Example"}}}"""
        ).mapIndexed { index, value -> index to Json.parseToJsonElement(value).jsonObject }.toMap()
        val reply = assistant(MessageStatus.Completed).copy(wireBlocks = raw)
        val request = json.encodeToString(CreateMessageRequest.serializer(), RequestBuilder.build(listOf(user("u"), reply), "继续", true, true))
        assertTrue(request.contains("signed-value"))
        assertTrue(request.contains("server_tool_use"))
        assertFalse(request.contains("私有推理"))
        assertTrue(request.contains("web_search"))
    }
}
