package com.glooboo.umessage.ui.onboarding

import com.glooboo.umessage.R

/** Who a row of the welcome card is about. */
internal enum class DemoSender { CONTACT, UNKNOWN, CODE }

/** One row of the welcome card: the sender's name ([senderLabel]) and whether its texts end up in Spam. */
internal data class DemoRow(val sender: DemoSender, val senderLabel: Int, val toSpam: Boolean)

/**
 * The welcome card's rows and the clock that lights them up one after another, kept apart from the drawing so they
 * can be tested. Time runs from 0 to [TOTAL_MS]; with reduced motion the card shows [TOTAL_MS], the final frame.
 * Within a row the sender lights up first, then the arrow, then the destination pill, which pops once. Then the row
 * rests so it can be read, and the next row starts.
 */
internal object WelcomeTimeline {
    /** The page's own fade-in is over by then. */
    const val START_DELAY_MS = 700

    /** One row, including its reading pause. */
    const val ROW_MS = 1500
    private const val ROW_COUNT = 3
    const val TOTAL_MS = START_DELAY_MS + ROW_COUNT * ROW_MS

    /** How far before the first row a replay by tap starts. */
    const val REPLAY_LEAD_MS = 200
    const val REPLAY_FROM_MS = START_DELAY_MS - REPLAY_LEAD_MS

    /** The alpha of a part that is not lit yet. */
    const val DIM = 0.3f

    private const val SENDER_END_MS = 250
    private const val ARROW_END_MS = 500
    private const val PILL_START_MS = 500
    private const val PILL_PEAK_MS = 700
    private const val PILL_END_MS = 800
    const val PILL_MIN_SCALE = 0.92f
    const val PILL_MAX_SCALE = 1.06f

    /**
     * Contacts go to Messages, an unknown sender to Spam, a verification code to Messages. The labels are the
     * texts of other screens (the Contacts permission, the spam reason, the code filter), so if one of them is
     * reworded there, the card changes with it.
     */
    val rows: List<DemoRow> = listOf(
        DemoRow(DemoSender.CONTACT, R.string.onb_perm_contacts, toSpam = false),
        DemoRow(DemoSender.UNKNOWN, R.string.spam_reason_unknown_sender, toSpam = true),
        DemoRow(DemoSender.CODE, R.string.ui_log_reason_otp, toSpam = false),
    )

    /** The real tab name, so the card always matches the bottom bar of the app. */
    fun destinationLabel(row: DemoRow): Int = if (row.toSpam) R.string.main_tab_blocked else R.string.main_tab_messages

    /** When row [index] (0, 1, 2) starts on the clock. */
    fun rowStart(index: Int): Int = START_DELAY_MS + index * ROW_MS

    /** The sender's avatar and name: [DIM] until the row starts, then lit. */
    fun senderAlpha(index: Int, timeMs: Float): Float = ramp(timeMs - rowStart(index), 0, SENDER_END_MS)

    /** The arrow lights up after the sender. */
    fun arrowAlpha(index: Int, timeMs: Float): Float = ramp(timeMs - rowStart(index), SENDER_END_MS, ARROW_END_MS)

    /** The destination pill lights up after the arrow. */
    fun pillAlpha(index: Int, timeMs: Float): Float = ramp(timeMs - rowStart(index), PILL_START_MS, PILL_END_MS)

    /** The pill pops while it lights up: small, then a little too big, then its normal size. */
    fun pillScale(index: Int, timeMs: Float): Float {
        val local = timeMs - rowStart(index)
        val grow = (local - PILL_START_MS) / (PILL_PEAK_MS - PILL_START_MS)
        val settle = (local - PILL_PEAK_MS) / (PILL_END_MS - PILL_PEAK_MS)
        return when {
            local < PILL_START_MS || local >= PILL_END_MS -> 1f
            local < PILL_PEAK_MS -> lerp(PILL_MIN_SCALE, PILL_MAX_SCALE, grow)
            else -> lerp(PILL_MAX_SCALE, 1f, settle)
        }
    }

    /** Whether the card starts on its final frame: reduced motion, or it already played (rotation, coming back). */
    fun startsFinished(reduceMotion: Boolean, alreadyPlayed: Boolean): Boolean = reduceMotion || alreadyPlayed

    private fun ramp(local: Float, fromMs: Int, toMs: Int): Float =
        lerp(DIM, 1f, ((local - fromMs) / (toMs - fromMs)).coerceIn(0f, 1f))

    private fun lerp(from: Float, to: Float, fraction: Float): Float = from * (1f - fraction) + to * fraction
}
