package com.deepseek.client.conversation

import com.deepseek.client.domain.ContentBlock
import com.deepseek.client.domain.Message
import com.deepseek.client.domain.ReplyView

object ReplyProjection {
    fun project(message: Message): ReplyView {
        val steps = message.blocks.filter { it !is ContentBlock.Text }
        val answer = message.blocks
            .filterIsInstance<ContentBlock.Text>()
            .joinToString("\n\n") { it.text }
            .trim()
            .takeIf { it.isNotEmpty() }
        return ReplyView(
            steps = steps,
            answerMarkdown = answer,
            sources = message.sources,
            searchResults = message.sources.distinctBy { it.url },
        )
    }
}
