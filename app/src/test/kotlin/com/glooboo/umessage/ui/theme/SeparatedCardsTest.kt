package com.glooboo.umessage.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.glooboo.umessage.ui.components.contrastRatio
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SeparatedCardsTest {

    /** SE-22: the dynamic dark scheme measured on the emulator, cards #282A2F on a #121318 page (1.29:1). */
    private val wallpaperDark = darkColorScheme(
        background = Color(0xFF121318),
        onSurface = Color(0xFFE3E2E9),
        surfaceContainerHigh = Color(0xFF282A2F),
        surfaceContainerHighest = Color(0xFF33353A),
    )

    @Test
    fun darkCardsReachTheMinimumContrast() {
        val lifted = wallpaperDark.withSeparatedCards()
        assertThat(contrastRatio(lifted.surfaceContainerHigh, lifted.background)).isAtLeast(DARK_CARD_CONTRAST)
        assertThat(contrastRatio(lifted.surfaceContainerHighest, lifted.background))
            .isGreaterThan(contrastRatio(lifted.surfaceContainerHigh, lifted.background))
    }

    @Test
    fun cardsAreLiftedOnlyAsFarAsNeeded() {
        val lifted = wallpaperDark.withSeparatedCards()
        // Still clearly a dark card, not a grey slab.
        assertThat(contrastRatio(lifted.surfaceContainerHigh, lifted.background)).isLessThan(DARK_CARD_CONTRAST + 0.2f)
    }

    @Test
    fun separatedEnoughDarkCardsAreKept() {
        val scheme = wallpaperDark.copy(
            surfaceContainerHigh = Color(0xFF3A3D45),
            surfaceContainerHighest = Color(0xFF4A4E58),
        )
        assertThat(scheme.withSeparatedCards().surfaceContainerHigh).isEqualTo(scheme.surfaceContainerHigh)
    }

    @Test
    fun lightSchemesAreLeftAlone() {
        val light = lightColorScheme()
        assertThat(light.withSeparatedCards()).isEqualTo(light)
    }
}
