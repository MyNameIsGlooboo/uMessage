package com.glooboo.umessage.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** WCAG minimum for body text. */
const val TEXT_CONTRAST = 4.5f

/** WCAG minimum for icons and large text. */
const val ICON_CONTRAST = 3f

private const val LUMINANCE_OFFSET = 0.05f

/** WCAG contrast ratio between two opaque colours, from 1 (same) to 21 (black on white). */
fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + LUMINANCE_OFFSET) / (minOf(la, lb) + LUMINANCE_OFFSET)
}

/**
 * [preferred] when it reaches [minRatio] on [background], else black or white, whichever reads better.
 *
 * VS-02, VS-35: the Wallpaper (dynamic) schemes at raised system contrast make the containers mid-tone and pick black
 * or white on-colours. A role meant for the container may then be drawn on the bar or the page, where it can vanish;
 * this keeps every such pairing readable whatever the scheme.
 */
fun readableOn(background: Color, preferred: Color, minRatio: Float = TEXT_CONTRAST): Color {
    if (contrastRatio(background, preferred) >= minRatio) return preferred
    val blackWins = contrastRatio(background, Color.Black) >= contrastRatio(background, Color.White)
    return if (blackWins) Color.Black else Color.White
}
