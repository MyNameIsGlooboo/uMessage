package com.glooboo.umessage.helpers

import androidx.core.graphics.ColorUtils

private const val DARK_TEXT = 0xFF0F172A.toInt()
private const val LIGHT_TEXT = 0xFFFFFFFF.toInt()

/** Text color with WCAG-style contrast on this background; the commons helper picks white on light blues. */
fun Int.readableTextColor(): Int {
    val background = ColorUtils.calculateLuminance(this)
    val dark = (background + 0.05) / (ColorUtils.calculateLuminance(DARK_TEXT) + 0.05)
    val light = (1.05) / (background + 0.05)
    return if (dark >= light) DARK_TEXT else LIGHT_TEXT
}
