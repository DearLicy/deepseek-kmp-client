package com.deepseek.client.runtime

import com.deepseek.client.mvi.*
import com.deepseek.client.storage.*
import com.deepseek.client.transport.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class ControllerPersistenceTest {
    @Test fun confirmed_delete_all_recovers_corrupt_history_without_overwriting_preferences() = runTest {
        var saved: String? = "corrupt"
        val storage = object : SnapshotStore {
            override suspend fun read() = saved
            override suspend fun write(value: String) { saved = value }
        }
        val keys = object : SecureKeyStore {
            override suspend fun read(): String? = null
            override suspend fun save(value: String) {}
            override suspend fun clear() {}
        }
        val transport = object : HttpTransport {
            override suspend fun get(url: String, headers: Map<String, String>) = error("unexpected")
            override suspend fun postSse(url: String, headers: Map<String, String>, body: String, onEvent: suspend (String, String) -> Boolean) = error("unexpected")
        }
        val controller = ChatController(backgroundScope, keys, storage, transport)
        controller.initialize()
        assertNotNull(controller.state.value.storageError)
        controller.dispatch(ChatIntent.SetTheme("dark"))
        controller.flush()
        assertEquals("corrupt", saved)
        controller.dispatch(ChatIntent.DeleteAll)
        controller.flush()
        assertEquals("dark", SnapshotCodec.decode(saved!!).preferences.theme)
        assertTrue(SnapshotCodec.decode(saved!!).sessions.isEmpty())
        assertNull(controller.state.value.storageError)
        controller.close()
    }
}
