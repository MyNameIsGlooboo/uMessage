package com.glooboo.umessage.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The welcome picture's clock: order, bounds and the final frame. */
class WelcomeTimelineTest {
    private val lanes = WelcomeTimeline.lanes

    @Test
    fun `a contact text goes to the Inbox, a stranger text to Spam, a stranger code to the Inbox`() {
        assertEquals(
            listOf(DemoMessage.CONTACT, DemoMessage.STRANGER, DemoMessage.CODE),
            lanes.map { it.message },
        )
        assertEquals(listOf(false, true, false), lanes.map { it.toSpam })
    }

    @Test
    fun `the texts leave one after another and all arrive before the end`() {
        assertEquals(lanes.map { it.startMs }.sorted(), lanes.map { it.startMs })
        assertTrue(lanes.all { it.startMs in 0 until it.endMs })
        assertTrue(lanes.last().endMs + WelcomeTimeline.ARRIVE_MS <= WelcomeTimeline.TOTAL_MS)
    }

    @Test
    fun `travel stays between 0 and 1 and grows with time`() {
        lanes.forEach { lane ->
            assertEquals(0f, WelcomeTimeline.travel(lane, -100f), 0f)
            assertEquals(1f, WelcomeTimeline.travel(lane, WelcomeTimeline.TOTAL_MS.toFloat()), 0f)
            val samples = (0..WelcomeTimeline.TOTAL_MS step 50).map { WelcomeTimeline.travel(lane, it.toFloat()) }
            assertEquals(samples.sorted(), samples)
        }
    }

    @Test
    fun `at the final frame every folder is fully lit and no text is left flying`() {
        val end = WelcomeTimeline.TOTAL_MS.toFloat()
        lanes.forEach {
            assertEquals(1f, WelcomeTimeline.folderGlow(it, end), 0f)
            assertEquals(0f, WelcomeTimeline.bubbleAlpha(it, end), 0f)
        }
    }

    @Test
    fun `at the start the folders are dim and no text shows yet`() {
        lanes.forEach {
            assertTrue(WelcomeTimeline.folderGlow(it, 0f) < 1f)
            assertEquals(0f, WelcomeTimeline.bubbleAlpha(it, 0f), 0f)
        }
    }

    @Test
    fun `reduced motion or an earlier play starts on the final frame`() {
        assertTrue(WelcomeTimeline.startsFinished(reduceMotion = true, alreadyPlayed = false))
        assertTrue(WelcomeTimeline.startsFinished(reduceMotion = false, alreadyPlayed = true))
        assertFalse(WelcomeTimeline.startsFinished(reduceMotion = false, alreadyPlayed = false))
    }
}
