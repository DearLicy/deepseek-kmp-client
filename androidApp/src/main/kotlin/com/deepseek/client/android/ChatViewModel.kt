package com.deepseek.client.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.deepseek.client.android.data.*
import com.deepseek.client.runtime.ChatController
import com.deepseek.client.transport.KtorTransport
import kotlinx.coroutines.launch

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    val controller = ChatController(
        viewModelScope, AndroidSecureKeyStore(application), AndroidSnapshotStore(application), KtorTransport(),
        attachmentStorage = AndroidAttachmentStorage(application), imagePreprocessor = AndroidImagePreprocessor,
    )
    init { viewModelScope.launch { controller.initialize() } }
    fun flush() { viewModelScope.launch { controller.flush() } }
    override fun onCleared() { controller.close() }
}
