package com.glooboo.umessage.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchExcerptTest {

    @Test
    fun shortBodiesAreKept() {
        assertEquals("Your code is 482913", searchExcerpt("Your code is 482913", "code"))
    }

    @Test
    fun longBodiesStartBeforeTheFirstMatch() {
        val body = "Dear customer, thank you for choosing our bank. Your one-time code is 482913 and it expires soon."
        val excerpt = searchExcerpt(body, "482913")
        assertTrue(excerpt.startsWith("…"))
        assertTrue(excerpt.contains("482913"))
        assertTrue(excerpt.length < body.length)
    }

    @Test
    fun newlinesBecomeSpaces() {
        assertEquals("a b", searchExcerpt("a" + System.lineSeparator().take(1) + "b", "zz"))
    }

    @Test
    fun wholeWordHitsAreTold() {
        assertTrue(isWholeWordHit("see you at 6", "at"))
        assertFalse(isWholeWordHit("battery", "at"))
        assertTrue(isWholeWordHit("code: 482913.", "482913"))
    }
}
