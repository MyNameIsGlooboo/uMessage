package com.glooboo.umessage.ui.thread

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Owner notes R2: the text selection inside the selected bubble must be easy to see and keep the text readable. */
class BubbleSelectionColorsTest {
    /** Selected-bubble container and text pairs: the app's gold theme and the M3 baseline, light and dark. */
    private val bubbles = listOf(
        Color(0xFFFFDDA8) to Color(0xFF3B2600),
        Color(0xFF4A3200) to Color(0xFFFFDDA8),
        Color(0xFFFFD8E4) to Color(0xFF31111D),
        Color(0xFF633B48) to Color(0xFFFFD8E4),
    )

    @Test
    fun `the highlight stands apart from the bubble and the text stays readable on it`() {
        bubbles.forEach { (container, content) ->
            val highlight = bubbleSelectionColors(content, Color.Magenta).backgroundColor.compositeOver(container)
            assertTrue("highlight on $container", contrastRatio(highlight, container) >= 1.9f)
            assertTrue("text on highlight for $container", contrastRatio(highlight, content) >= 3f)
        }
    }

    @Test
    fun `the handles take the accent`() {
        assertEquals(Color.Magenta, bubbleSelectionColors(Color.Black, Color.Magenta).handleColor)
    }
}
