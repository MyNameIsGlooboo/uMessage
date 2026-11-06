package com.glooboo.umessage.ui.thread

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadTextSelectionTest {
    @Test
    fun `the one selected message with text can be selected in place`() {
        assertTrue(canSelectTextInPlace(setOf(7L), 7L, "Your code is 1234"))
    }

    @Test
    fun `nothing selected or another message selected keeps the bubble as it is`() {
        assertFalse(canSelectTextInPlace(emptySet(), 7L, "Hello"))
        assertFalse(canSelectTextInPlace(setOf(8L), 7L, "Hello"))
    }

    @Test
    fun `two selected messages keep their normal gestures`() {
        assertFalse(canSelectTextInPlace(setOf(7L, 8L), 7L, "Hello"))
    }

    @Test
    fun `a message without text has nothing to select`() {
        assertFalse(canSelectTextInPlace(setOf(7L), 7L, ""))
        assertFalse(canSelectTextInPlace(setOf(7L), 7L, "  \n"))
    }
}
