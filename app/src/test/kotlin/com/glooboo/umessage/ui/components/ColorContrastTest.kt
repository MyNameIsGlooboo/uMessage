package com.glooboo.umessage.ui.components

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ColorContrastTest {

    @Test
    fun blackOnWhiteIsTwentyOne() {
        assertThat(contrastRatio(Color.Black, Color.White)).isWithin(0.01f).of(21f)
        assertThat(contrastRatio(Color.White, Color.Black)).isWithin(0.01f).of(21f)
    }

    @Test
    fun sameColourIsOne() {
        assertThat(contrastRatio(Color.Gray, Color.Gray)).isWithin(0.001f).of(1f)
    }

    @Test
    fun readablePairingIsKept() {
        val navy = Color(0xFF0F172A)
        assertThat(readableOn(Color(0xFFC5D5FA), navy)).isEqualTo(navy)
    }

    /** VS-02, Wallpaper theme in dark mode: a black on-colour meant for the pill landed on the dark bar. */
    @Test
    fun blackLabelOnDarkBarTurnsWhite() {
        assertThat(readableOn(Color(0xFF1E1F25), Color.Black)).isEqualTo(Color.White)
    }

    /** VS-02, light mode with raised contrast: a white on-colour landed on the light bar. */
    @Test
    fun whiteLabelOnLightBarTurnsBlack() {
        assertThat(readableOn(Color(0xFFEEEDF4), Color.White)).isEqualTo(Color.Black)
    }

    @Test
    fun iconThresholdIsLowerThanText() {
        // About 3.5:1: enough for an icon, not for text.
        val background = Color(0.55f, 0.55f, 0.55f)
        val grey = Color.White
        val ratio = contrastRatio(background, grey)
        assertThat(ratio).isAtLeast(ICON_CONTRAST)
        assertThat(ratio).isLessThan(TEXT_CONTRAST)
        assertThat(readableOn(background, grey, ICON_CONTRAST)).isEqualTo(grey)
        assertThat(readableOn(background, grey)).isEqualTo(Color.Black)
    }
}
