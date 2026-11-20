package com.deepseek.client.storage

import com.deepseek.client.attachments.Attachment
import com.deepseek.client.domain.*
import com.deepseek.client.mvi.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class LocalSnapshot(
    val version: Int = 1,
    val sessions: List<Session> = emptyList(),
    val drafts: Map<String, String> = emptyMap(),
    val preferences: Preferences = Preferences(),
    val draftAttachments: Map<String, List<Attachment>> = emptyMap(),
)

object SnapshotCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(value: LocalSnapshot): String = json.encodeToString(LocalSnapshot.serializer(), value)
    fun decode(text: String): LocalSnapshot {
        val snapshot = json.decodeFromString(LocalSnapshot.serializer(), text)
        require(snapshot.version == 1) { "Unsupported history version" }
        require(snapshot.sessions.map { it.id }.distinct().size == snapshot.sessions.size) { "Duplicate sessions" }
        return snapshot.copy(sessions = snapshot.sessions.map { session -> session.copy(messages = session.messages.map { msg ->
            if (msg.status != MessageStatus.Streaming) msg else msg.copy(status = MessageStatus.Failed, error = "应用关闭导致生成中断", blocks = msg.blocks.map { block -> when (block) {
                is ContentBlock.Thinking -> block.copy(endedAt = block.endedAt ?: block.startedAt ?: msg.createdAt)
                is ContentBlock.Search -> block.copy(status = if (block.status == ToolStatus.Wip) ToolStatus.Failed else block.status)
                is ContentBlock.ReadLink -> block.copy(status = if (block.status == ToolStatus.Wip) ToolStatus.Failed else block.status)
                else -> block
            } })
        }) })
    }
}

interface SecureKeyStore {
    suspend fun read(): String?
    suspend fun save(value: String)
    suspend fun clear()
}

interface SnapshotStore {
    suspend fun read(): String?
    suspend fun write(value: String)
}
