package com.deepseek.client.storage

import com.deepseek.client.domain.*
import com.deepseek.client.attachments.Attachment
import com.deepseek.client.mvi.*
import kotlin.test.*

class SnapshotTest {
    @Test fun interrupted_reply_is_restored_as_failed_and_retryable() {
        val active = ChatReducer.reduce(AppState(apiKeyConfigured = true), ChatIntent.SendMessage(TurnId("t"), "问题"), 10).state
        val restored = SnapshotCodec.decode(SnapshotCodec.encode(LocalSnapshot(sessions = active.sessions)))
        assertEquals(MessageStatus.Failed, restored.sessions.single().messages.last().status)
        assertNotNull(restored.sessions.single().messages.last().error)
    }
    @Test fun completed_content_signed_blocks_preferences_and_drafts_roundtrip() {
        val attachment = Attachment("attachment-one", "photo.jpg", 12, "image/jpeg")
        val snapshot = LocalSnapshot(drafts = mapOf("new" to "草稿"),
            preferences = Preferences(thinking = true, search = true, theme = "dark", fontScale = 1.2f),
            draftAttachments = mapOf("new" to listOf(attachment)))
        assertEquals(snapshot, SnapshotCodec.decode(SnapshotCodec.encode(snapshot)))
        val encoded = SnapshotCodec.encode(snapshot)
        assertFalse(encoded.contains("apiKey", ignoreCase = true))
        assertFalse(encoded.contains("activeTurn"))
    }
    @Test fun unknown_versions_and_duplicate_ids_fail_closed() {
        assertFails { SnapshotCodec.decode("""{"version":99}""") }
        val session = Session(SessionId("same"), "title", 0, 0)
        assertFails { SnapshotCodec.decode(SnapshotCodec.encode(LocalSnapshot(sessions = listOf(session, session)))) }
        assertFails { SnapshotCodec.decode("broken") }
    }
}
