package com.glooboo.umessage.ui.onboarding

import com.glooboo.umessage.R
import com.glooboo.umessage.ui.onboarding.WelcomeTimeline.DIM
import com.glooboo.umessage.ui.onboarding.WelcomeTimeline.ROW_MS
import com.glooboo.umessage.ui.onboarding.WelcomeTimeline.TOTAL_MS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The welcome card's rows and clock: order, labels, bounds and the final frame. */
class WelcomeTimelineTest {
    private val rows = WelcomeTimeline.rows
    private val indices = rows.indices
    private val total = TOTAL_MS.toFloat()

    @Test
    fun `the rows are contacts to Messages, an unknown sender to Spam, a code to Messages`() {
        assertEquals(listOf(DemoSender.CONTACT, DemoSender.UNKNOWN, DemoSender.CODE), rows.map { it.sender })
        assertEquals(
            listOf(R.string.onb_perm_contacts, R.string.spam_reason_unknown_sender, R.string.ui_log_reason_otp),
            rows.map { it.senderLabel },
        )
        assertEquals(
            listOf(R.string.main_tab_messages, R.string.main_tab_blocked, R.string.main_tab_messages),
            rows.map { WelcomeTimeline.destinationLabel(it) },
        )
    }

    @Test
    fun `at the start every part is dim and every pill is at its normal size`() {
        indices.forEach {
            assertEquals(DIM, WelcomeTimeline.senderAlpha(it, 0f), 0f)
            assertEquals(DIM, WelcomeTimeline.arrowAlpha(it, 0f), 0f)
            assertEquals(DIM, WelcomeTimeline.pillAlpha(it, 0f), 0f)
            assertEquals(1f, WelcomeTimeline.pillScale(it, 0f), 0f)
        }
    }

    @Test
    fun `at the final frame every part is fully lit and every pill is at its normal size`() {
        indices.forEach {
            assertEquals(1f, WelcomeTimeline.senderAlpha(it, total), 0f)
            assertEquals(1f, WelcomeTimeline.arrowAlpha(it, total), 0f)
            assertEquals(1f, WelcomeTimeline.pillAlpha(it, total), 0f)
            assertEquals(1f, WelcomeTimeline.pillScale(it, total), 0f)
        }
    }

    @Test
    fun `the rows play one after another`() {
        for (i in 0 until rows.size - 1) {
            val at = (WelcomeTimeline.rowStart(i) + READING_PAUSE_FROM_MS).toFloat()
            assertEquals(1f, WelcomeTimeline.senderAlpha(i, at), 0f)
            assertEquals(1f, WelcomeTimeline.arrowAlpha(i, at), 0f)
            assertEquals(1f, WelcomeTimeline.pillAlpha(i, at), 0f)
            assertEquals(DIM, WelcomeTimeline.senderAlpha(i + 1, at), 0f)
            assertEquals(DIM, WelcomeTimeline.arrowAlpha(i + 1, at), 0f)
            assertEquals(DIM, WelcomeTimeline.pillAlpha(i + 1, at), 0f)
        }
    }

    @Test
    fun `inside a row the sender lights first, then the arrow, then the pill`() {
        indices.forEach {
            val start = WelcomeTimeline.rowStart(it)
            val senderDone = (start + SENDER_DONE_MS).toFloat()
            assertEquals(1f, WelcomeTimeline.senderAlpha(it, senderDone), 0f)
            assertEquals(DIM, WelcomeTimeline.arrowAlpha(it, senderDone), 0f)
            val arrowDone = (start + ARROW_DONE_MS).toFloat()
            assertEquals(1f, WelcomeTimeline.arrowAlpha(it, arrowDone), 0f)
            assertEquals(DIM, WelcomeTimeline.pillAlpha(it, arrowDone), 0f)
        }
    }

    @Test
    fun `alphas stay between dim and full and never fall, the pill scale stays in its range`() {
        indices.forEach { row ->
            val times = (-STEP_MS..TOTAL_MS + STEP_MS step STEP_MS).map { it.toFloat() }
            listOf(WelcomeTimeline::senderAlpha, WelcomeTimeline::arrowAlpha, WelcomeTimeline::pillAlpha)
                .forEach { alphaOf ->
                    val alphas = times.map { alphaOf(row, it) }
                    assertTrue(alphas.all { it in DIM..1f })
                    assertEquals(alphas.sorted(), alphas)
                }
            val scales = times.map { WelcomeTimeline.pillScale(row, it) }
            assertTrue(scales.all { it in WelcomeTimeline.PILL_MIN_SCALE..WelcomeTimeline.PILL_MAX_SCALE })
        }
    }

    @Test
    fun `the pill pops once while it lights up`() {
        val start = WelcomeTimeline.rowStart(0)
        val popStart = WelcomeTimeline.pillScale(0, (start + ARROW_DONE_MS).toFloat())
        assertEquals(WelcomeTimeline.PILL_MIN_SCALE, popStart, 0f)
        assertTrue(WelcomeTimeline.pillScale(0, (start + PILL_PEAK_MS).toFloat()) > 1f)
    }

    @Test
    fun `the whole card takes 5200 ms, and reduced motion or an earlier play starts on the final frame`() {
        assertEquals(5200, TOTAL_MS)
        assertEquals(WelcomeTimeline.START_DELAY_MS + rows.size * ROW_MS, TOTAL_MS)
        assertTrue(WelcomeTimeline.startsFinished(reduceMotion = true, alreadyPlayed = false))
        assertTrue(WelcomeTimeline.startsFinished(reduceMotion = false, alreadyPlayed = true))
        assertFalse(WelcomeTimeline.startsFinished(reduceMotion = false, alreadyPlayed = false))
    }

    private companion object {
        const val SENDER_DONE_MS = 250
        const val ARROW_DONE_MS = 500
        const val PILL_PEAK_MS = 700
        const val READING_PAUSE_FROM_MS = 800
        const val STEP_MS = 50
    }
}
