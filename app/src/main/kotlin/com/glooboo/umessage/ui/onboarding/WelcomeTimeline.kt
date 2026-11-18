package com.glooboo.umessage.ui.onboarding

/** The three little texts of the welcome picture. */
internal enum class DemoMessage { CONTACT, STRANGER, CODE }

/** One text that flies from its sender to its folder between [startMs] and [endMs] of the picture's clock. */
internal data class DemoLane(
    val message: DemoMessage,
    val startMs: Int,
    val endMs: Int,
    val toSpam: Boolean,
)

/**
 * The timeline of the welcome picture, kept apart from the drawing so it can be tested. Time runs from 0 to
 * [TOTAL_MS]; with reduced motion the picture shows [TOTAL_MS], the final frame.
 */
internal object WelcomeTimeline {
    const val TOTAL_MS = 2700

    /** How long a folder takes to light up once its text has arrived. */
    const val ARRIVE_MS = 250

    /** A text from a saved contact, then one from a stranger, then a stranger's code that still reaches the Inbox. */
    val lanes: List<DemoLane> = listOf(
        DemoLane(DemoMessage.CONTACT, startMs = 300, endMs = 1100, toSpam = false),
        DemoLane(DemoMessage.STRANGER, startMs = 900, endMs = 1700, toSpam = true),
        DemoLane(DemoMessage.CODE, startMs = 1500, endMs = 2300, toSpam = false),
    )

    /** How far the text has flown, 0 (at its sender) to 1 (at its folder), eased at both ends. */
    fun travel(lane: DemoLane, timeMs: Float): Float {
        val linear = ((timeMs - lane.startMs) / (lane.endMs - lane.startMs)).coerceIn(0f, 1f)
        return linear * linear * (SMOOTH_STEP_BASE - 2f * linear)
    }

    /** The text is drawn only while it is on its way: hidden before it starts, and fading out as it arrives. */
    fun bubbleAlpha(lane: DemoLane, timeMs: Float): Float {
        if (timeMs < lane.startMs) return 0f
        val travel = travel(lane, timeMs)
        return if (travel < FADE_FROM) 1f else ((1f - travel) / (1f - FADE_FROM)).coerceIn(0f, 1f)
    }

    /** How lit the folder is, [DIM] (waiting) to 1 (the text has arrived). */
    fun folderGlow(lane: DemoLane, timeMs: Float): Float {
        val arrived = ((timeMs - lane.endMs) / ARRIVE_MS).coerceIn(0f, 1f)
        return DIM + (1f - DIM) * arrived
    }

    /** Whether the picture starts on its final frame: reduced motion, or it already played (rotation, coming back). */
    fun startsFinished(reduceMotion: Boolean, alreadyPlayed: Boolean): Boolean = reduceMotion || alreadyPlayed

    /** The "3" of the smooth-step curve 3t² − 2t³. */
    private const val SMOOTH_STEP_BASE = 3f
    private const val FADE_FROM = 0.8f
    private const val DIM = 0.35f
}
