package com.deepseek.client.search

import com.deepseek.client.domain.Source

class SourceRegistry {
    private val sources = mutableListOf<Source>()
    private val byUrl = mutableMapOf<String, Source>()

    val all: List<Source>
        get() = sources.toList()

    fun add(
        url: String,
        title: String?,
        publishedAt: String?,
        snippet: String? = null,
        siteIcon: String? = null,
        siteName: String? = null,
    ): Source? {
        if (!isWebUrl(url)) return null
        val normalizedSiteName = siteName?.takeIf { it.isNotBlank() } ?: siteNameFromUrl(url)
        val existing = byUrl[url]
        if (existing != null) {
            val updated = existing.copy(
                title = if (existing.title == url || existing.title.isBlank()) title?.takeIf { it.isNotBlank() } ?: existing.title else existing.title,
                publishedAt = existing.publishedAt ?: publishedAt,
                snippet = existing.snippet ?: snippet?.takeIf { it.isNotBlank() },
                siteIcon = existing.siteIcon ?: siteIcon?.takeIf { it.isNotBlank() },
                siteName = if (existing.siteName == siteNameFromUrl(url)) normalizedSiteName else existing.siteName,
            )
            sources[sources.indexOf(existing)] = updated
            byUrl[url] = updated
            return byUrl[url]
        }
        val source = Source(
            url = url,
            title = title?.takeIf { it.isNotBlank() } ?: url,
            siteName = normalizedSiteName,
            publishedAt = publishedAt,
            snippet = snippet?.takeIf { it.isNotBlank() },
            siteIcon = siteIcon?.takeIf { it.isNotBlank() },
            index = sources.size + 1,
        )
        sources += source
        byUrl[url] = source
        return source
    }

    companion object {
        fun isWebUrl(url: String): Boolean =
            url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

        fun siteNameFromUrl(url: String): String = runCatching {
            url.substringAfter("://").substringBefore("/").removePrefix("www.")
        }.getOrDefault("")
    }
}
