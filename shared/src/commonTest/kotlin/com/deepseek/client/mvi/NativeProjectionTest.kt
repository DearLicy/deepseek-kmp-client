package com.deepseek.client.mvi

import com.deepseek.client.bridge.NativeProjection
import com.deepseek.client.domain.*
import kotlinx.serialization.json.*
import kotlin.test.*

class NativeProjectionTest {
    @Test fun swift_projection_uses_flat_ids_and_same_reduced_content() {
        var state = ChatReducer.reduce(AppState(apiKeyConfigured = true, apiKeyMasked = "•••• 1234"), ChatIntent.SendMessage(TurnId("ios"), "hi"), 100).state
        state = ChatReducer.reduce(state, ChatAction.BlockStarted(TurnId("ios"), 0, ContentBlock.Thinking(BlockId("b"), 0, "分析"), "b"), 101).state
        state = ChatReducer.reduce(state, ChatIntent.ToggleThinkingBlock(BlockId("b")), 102).state
        val encoded = NativeProjection.encode(state)
        val view = Json.parseToJsonElement(encoded).jsonObject
        assertEquals(state.selectedSessionId!!.value, view["selectedId"]!!.jsonPrimitive.content)
        assertEquals("•••• 1234", view["keyMasked"]!!.jsonPrimitive.content)
        val assistant = view["sessions"]!!.jsonArray.first().jsonObject["messages"]!!.jsonArray.last().jsonObject
        val block = assistant["blocks"]!!.jsonArray.single().jsonObject
        assertEquals("thinking", block["kind"]!!.jsonPrimitive.content)
        assertTrue(block["collapsed"]!!.jsonPrimitive.boolean)
        assertEquals("分析", block["text"]!!.jsonPrimitive.content)
        assertFalse(encoded.contains("wireBlocks"))
        assertFalse(encoded.contains("signature"))
    }
}
