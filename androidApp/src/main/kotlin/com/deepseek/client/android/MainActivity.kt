package com.deepseek.client.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.deepseek.client.android.ui.DeepSeekApp
import com.deepseek.client.android.ui.DeepSeekTheme

class MainActivity : ComponentActivity() {
    private lateinit var model: ChatViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this)[ChatViewModel::class.java]
        setContent {
            val state by model.controller.state.collectAsState()
            DeepSeekTheme(state.preferences.theme) { DeepSeekApp(model.controller) }
        }
    }
    override fun onStop() { super.onStop(); model.flush() }
}
