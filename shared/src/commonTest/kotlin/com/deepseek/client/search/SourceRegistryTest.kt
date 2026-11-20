package com.deepseek.client.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SourceRegistryTest {
    @Test
    fun duplicates_keep_first_index_and_fill_title() {
        val registry = SourceRegistry()
        val first = registry.add("https://example.com/a", null, null)
        val second = registry.add("https://example.com/a", "Example", "2026-11-19")
        assertEquals(1, first?.index)
        assertEquals(1, second?.index)
        assertEquals(1, registry.all.size)
        assertEquals("Example", registry.all.single().title)
    }

    @Test
    fun rejects_non_web_urls() {
        val registry = SourceRegistry()
        assertNull(registry.add("javascript:alert(1)", "bad", null))
        assertEquals(0, registry.all.size)
    }
}
