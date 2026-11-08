package com.glooboo.umessage.ui.thread

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import com.glooboo.umessage.ui.components.rememberReduceMotion

/** The short tint on the bubble a chat jumped to (from Starred, search or notes). */
object ThreadFlash {
    /** How long the tint stays before it fades away. */
    const val DURATION_MS = 1_500L

    /** The strongest the tint gets: a hint, not a highlighter. */
    const val PEAK_ALPHA = 0.22f

    private const val FADE_IN_MS = 120
    private const val FADE_OUT_MS = 600

    fun targetAlpha(active: Boolean): Float = if (active) PEAK_ALPHA else 0f

    /** Fade lengths in milliseconds; with animations switched off the tint appears and goes at once. */
    fun fadeMillis(active: Boolean, reduceMotion: Boolean): Int =
        if (reduceMotion) 0 else if (active) FADE_IN_MS else FADE_OUT_MS
}

/** The row tint for [active] (this bubble was just jumped to); fully transparent otherwise. */
@Composable
internal fun flashColor(active: Boolean, tint: Color): Color {
    val reduceMotion = rememberReduceMotion()
    val alpha by animateFloatAsState(
        targetValue = ThreadFlash.targetAlpha(active),
        animationSpec = tween(ThreadFlash.fadeMillis(active, reduceMotion)),
        label = "jump flash",
    )
    return tint.copy(alpha = alpha)
}
