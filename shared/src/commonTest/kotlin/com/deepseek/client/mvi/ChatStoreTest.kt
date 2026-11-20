package com.deepseek.client.mvi

import com.deepseek.client.domain.BlockId
import com.deepseek.client.domain.ContentBlock
import com.deepseek.client.domain.Message
import com.deepseek.client.domain.MessageId
import com.deepseek.client.domain.MessageRole
import com.deepseek.client.domain.MessageStatus
import com.deepseek.client.domain.SessionId
import com.deepseek.client.domain.Source
import com.deepseek.client.domain.TextCitation
import com.deepseek.client.domain.TurnId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChatStoreTest {
    @Test
    fun stream_events_build_one_assistant_message() = runTest {
        val store = ChatStore(AppState(apiKeyConfigured = true))
        val turnId = TurnId("turn-1")
        store.dispatch(ChatIntent.ApiKeyState(true, "sk-****1234"))
        store.dispatch(ChatIntent.SendMessage(turnId, "hi"))
        val sessionId = store.state.value.selectedSessionId!!

        val startedAt = 0L
        store.dispatch(
            ChatAction.TurnStarted(
                turnId = turnId,
                sessionId = sessionId,
                message = Message(
                    id = MessageId("assistant-1"),
                    role = MessageRole.Assistant,
                    createdAt = startedAt,
                    status = MessageStatus.Streaming,
                ),
                startedAt = startedAt,
            ),
        )
        store.dispatch(ChatAction.BlockStarted(turnId, 0, ContentBlock.Thinking(BlockId("b-think"), 0, ""), "b-think"))
        store.dispatch(ChatAction.ThinkingDelta(turnId, 0, "b-think", "用户说 "))
        store.dispatch(ChatAction.ThinkingDelta(turnId, 0, "b-think", "hi"))
        store.dispatch(ChatAction.BlockStarted(turnId, 1, ContentBlock.Text(BlockId("b-text"), 1, ""), "b-text"))
        store.dispatch(ChatAction.TextDelta(turnId, 1, "b-text", "Hello"))
        store.dispatch(
            ChatAction.CitationDelta(
                turnId = turnId,
                index = 1,
                blockKey = "b-text",
                citations = listOf(TextCitation("https://example.com", "Example", "Hello")),
                sources = listOf(Source("https://example.com", "Example", "example.com", null, null, null, 1)),
            ),
        )
        store.dispatch(ChatAction.TurnCompleted(turnId, "Hi greeting", "end_turn"))

        val session = store.state.value.sessions.first { it.id == sessionId }
        assertEquals("Hi greeting", session.title)
        assertEquals(2, session.messages.size)
        val message = session.messages.last()
        assertEquals(MessageStatus.Completed, message.status)
        assertEquals(2, message.blocks.size)
        assertEquals("用户说 hi", (message.blocks[0] as ContentBlock.Thinking).text)
        assertEquals("Hello", (message.blocks[1] as ContentBlock.Text).text)
        assertEquals(1, message.sources.size)
        assertEquals(null, store.state.value.activeTurnId)
    }

    @Test
    fun late_events_do_not_mutate_current_turn() = runTest {
        val store = ChatStore(AppState(apiKeyConfigured = true))
        val stale = TurnId("old")
        store.dispatch(ChatAction.TextDelta(stale, 0, "old-block", "late"))
        assertTrue(store.state.value.sessions.isEmpty())
        assertTrue(store.isLate(stale))
    }

    @Test
    fun duplicate_block_start_is_idempotent() = runTest {
        val store = ChatStore(AppState(apiKeyConfigured = true))
        val turnId = TurnId("turn-1")
        store.dispatch(ChatIntent.SendMessage(turnId, "hi"))
        val sessionId = store.state.value.selectedSessionId!!
        store.dispatch(
            ChatAction.TurnStarted(
                turnId,
                sessionId,
                Message(MessageId("a"), MessageRole.Assistant, 0, MessageStatus.Streaming),
                0,
            ),
        )
        repeat(2) {
            store.dispatch(ChatAction.BlockStarted(turnId, 0, ContentBlock.Text(BlockId("b"), 0, ""), "b"))
        }
        store.dispatch(ChatAction.TextDelta(turnId, 0, "b", "x"))
        val session = store.state.value.sessions.first { it.id == sessionId }
        assertEquals(1, session.messages.last().blocks.size)
        assertEquals("x", (session.messages.last().blocks.single() as ContentBlock.Text).text)
    }
}
