package com.deepseek.client.mvi

import com.deepseek.client.domain.*
import com.deepseek.client.attachments.Attachment
import kotlin.test.*

class ReducerRegressionTest {
    private val turn = TurnId("one")
    private fun start() = ChatReducer.reduce(AppState(ready = true, apiKeyConfigured = true), ChatIntent.SendMessage(turn, "问题一"), 100).state
    @Test fun replay_is_deterministic_and_creates_stable_message_ids() {
        val input = AppState(apiKeyConfigured = true)
        val a = ChatReducer.reduce(input, ChatIntent.SendMessage(turn, "问题一"), 100)
        assertEquals(a, ChatReducer.reduce(input, ChatIntent.SendMessage(turn, "问题一"), 100))
        assertEquals(listOf("one-user", "one-assistant"), a.state.sessions.single().messages.map { it.id.value })
        assertEquals(1, a.effects.size)
        assertEquals(a.state, ChatReducer.reduce(a.state, ChatIntent.SendMessage(TurnId("two"), "重复点击"), 101).state)
    }
    @Test fun background_reply_keeps_its_owner_and_manual_title() {
        var state = start()
        val owner = state.selectedSessionId!!
        state = ChatReducer.reduce(state, ChatIntent.RenameSession(owner, "手动标题"), 101).state
        state = ChatReducer.reduce(state, ChatIntent.NewSession, 102).state
        state = ChatReducer.reduce(state, ChatAction.BlockStarted(turn, 0, ContentBlock.Text(BlockId("a"), 0, ""), "a"), 103).state
        state = ChatReducer.reduce(state, ChatAction.TextDelta(turn, 0, "a", "回答一"), 104).state
        state = ChatReducer.reduce(state, ChatAction.TurnCompleted(turn, "服务器标题", "end_turn"), 105).state
        assertNull(state.selectedSessionId)
        assertEquals("手动标题", state.sessions.single().title)
        assertEquals("回答一", (state.sessions.single().messages.last().blocks.single() as ContentBlock.Text).text)
        assertNull(state.activeTurnId)
    }
    @Test fun deleting_generating_session_cancels_and_rejects_late_events() {
        val state = start()
        val transition = ChatReducer.reduce(state, ChatIntent.DeleteSession(state.selectedSessionId!!), 120)
        assertEquals(listOf(ChatEffect.CancelTurn(turn)), transition.effects)
        assertTrue(transition.state.sessions.isEmpty())
        assertNull(transition.state.activeTurnId)
        assertEquals(transition.state, ChatReducer.reduce(transition.state, ChatAction.TurnCompleted(turn, "late", "end_turn"), 130).state)
    }
    @Test fun stop_closes_thinking_and_retains_partial_answer() {
        var state = start()
        state = ChatReducer.reduce(state, ChatAction.BlockStarted(turn, 0, ContentBlock.Thinking(BlockId("t"), 0, "分析"), "t"), 101).state
        state = ChatReducer.reduce(state, ChatAction.BlockStarted(turn, 1, ContentBlock.Text(BlockId("a"), 1, "部分答案"), "a"), 102).state
        state = ChatReducer.reduce(state, ChatIntent.CancelTurn(turn), 140).state
        val reply = state.sessions.single().messages.last()
        assertEquals(MessageStatus.Cancelled, reply.status)
        assertEquals(140, (reply.blocks[0] as ContentBlock.Thinking).endedAt)
        assertEquals("部分答案", (reply.blocks[1] as ContentBlock.Text).text)
    }
    @Test fun drafts_are_isolated_between_welcome_and_conversations() {
        var state = start()
        val id = state.selectedSessionId!!
        state = ChatReducer.reduce(state, ChatIntent.InputChanged("已有会话草稿"), 101).state
        state = ChatReducer.reduce(state, ChatIntent.NewSession, 102).state
        state = ChatReducer.reduce(state, ChatIntent.InputChanged("新会话草稿"), 103).state
        state = ChatReducer.reduce(state, ChatIntent.SelectSession(id), 104).state
        assertEquals("已有会话草稿", state.draft)
        assertEquals("新会话草稿", ChatReducer.reduce(state, ChatIntent.NewSession, 105).state.draft)
    }

    @Test fun attachment_only_turn_keeps_local_file_and_clears_composer() {
        val image = Attachment("attachment-one", "photo.jpg", 12, "image/jpeg")
        var state = ChatReducer.reduce(AppState(apiKeyConfigured = true),
            ChatIntent.AttachmentStaged(null, image), 10).state
        val transition = ChatReducer.reduce(state, ChatIntent.SendMessage(turn, ""), 11)
        state = transition.state
        assertEquals(emptyList(), state.attachments)
        assertEquals(listOf(image), state.sessions.single().messages.first().attachments)
        assertEquals("photo.jpg", state.sessions.single().title)
        assertTrue((transition.effects.single() as ChatEffect.SendMessage).userId.value.endsWith("-user"))
        assertEquals(MessageStatus.Streaming, state.sessions.single().messages.last().status)
    }

    @Test fun failed_reply_can_restore_original_text_and_attachments() {
        val image = Attachment("attachment-one", "photo.jpg", 12, "image/jpeg")
        var state = ChatReducer.reduce(AppState(apiKeyConfigured = true),
            ChatIntent.AttachmentStaged(null, image), 10).state
        state = ChatReducer.reduce(state, ChatIntent.SendMessage(turn, "看图"), 11).state
        state = ChatReducer.reduce(state, ChatAction.TurnFailed(turn, "上传失败"), 12).state
        state = ChatReducer.reduce(state, ChatIntent.EditForResend(MessageId("one-assistant")), 13).state
        assertEquals("看图", state.draft)
        assertEquals(listOf(image), state.attachments)
    }
    @Test fun absent_key_never_emits_network_effect() {
        val result = ChatReducer.reduce(AppState(), ChatIntent.SendMessage(turn, "hi"), 100)
        assertTrue(result.effects.isEmpty())
        assertTrue(result.state.sessions.isEmpty())
        assertNotNull(result.state.transient.message)
    }
}
