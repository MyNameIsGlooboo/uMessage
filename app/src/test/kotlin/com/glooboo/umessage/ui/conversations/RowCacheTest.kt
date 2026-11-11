package com.glooboo.umessage.ui.conversations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** main-3: a row is derived again only when its input changed. */
class RowCacheTest {

    @Test
    fun sameInputIsNotComputedAgain() {
        val cache = RowCache<Long, String, String>()
        var calls = 0
        repeat(3) { cache.get(1L, "a") { calls++; "row-a" } }
        assertEquals(1, calls)
    }

    @Test
    fun changedInputIsComputedAgain() {
        val cache = RowCache<Long, String, String>()
        assertEquals("one", cache.get(1L, "a") { "one" })
        assertEquals("two", cache.get(1L, "b") { "two" })
        assertEquals("two", cache.get(1L, "b") { "never" })
    }

    @Test
    fun retainOnlyForgetsGoneKeys() {
        val cache = RowCache<Long, String, String>()
        cache.get(1L, "a") { "x" }
        cache.get(2L, "a") { "y" }
        cache.retainOnly(setOf(2L))
        assertNull(cache.lookup(1L, "a"))
        assertEquals("y", cache.lookup(2L, "a"))
        assertEquals(1, cache.size)
    }
}
