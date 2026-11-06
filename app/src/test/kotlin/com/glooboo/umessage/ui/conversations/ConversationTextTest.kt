package com.glooboo.umessage.ui.conversations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTextTest {

    @Test
    fun avatarToneIsStableAndIgnoresCaseAndSpaces() {
        val size = 8
        val sara = avatarToneIndex("Sara Ahmadi", size)
        assertTrue(sara in 0 until size)
        assertEquals(sara, avatarToneIndex("sara ahmadi ", size))
        assertEquals(sara, avatarToneIndex("Sara Ahmadi", size))
        // The names in the review spread over more than one tone.
        val names = listOf("Sara Ahmadi", "Mom", "Reza Work", "Test Friend", "علی رضایی")
        val tones = names.map { avatarToneIndex(it, size) }
        assertTrue(tones.toSet().size > 1)
    }

    @Test
    fun listReturnsToTopOnlyForAUserAtTheTop() {
        assertTrue(shouldReturnToTop(previousTop = 1, currentTop = 2, userWasAtTop = true))
        assertTrue(shouldReturnToTop(previousTop = null, currentTop = 2, userWasAtTop = true))
        assertFalse(shouldReturnToTop(previousTop = 1, currentTop = 2, userWasAtTop = false))
        assertFalse(shouldReturnToTop(previousTop = 2, currentTop = 2, userWasAtTop = true))
        assertFalse(shouldReturnToTop(previousTop = 2, currentTop = null, userWasAtTop = true))
    }
}
