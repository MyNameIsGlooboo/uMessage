package com.glooboo.umessage.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.ui.onboarding.BottomBar
import com.glooboo.umessage.ui.theme.UMessageTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Landscape on phones: bubbles stop growing with the window, and the onboarding buttons stay reachable. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w800dp-h360dp-land")
class AdaptiveLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a bubble keeps 80 percent of a narrow window and is capped at 480dp in a wide one`() {
        assertEquals(288.dp, bubbleMaxWidthDp(360))
        assertEquals(480.dp, bubbleMaxWidthDp(800))
        assertEquals(480.dp, bubbleMaxWidthDp(600))
        assertEquals(480.dp, bubbleMaxWidthDp(1200))
    }

    @Test
    fun `the bubble width in a landscape phone window is the cap`() {
        var width = 0.dp
        compose.setContent { width = rememberBubbleMaxWidth() }
        compose.waitForIdle()
        assertTrue("got $width", width <= BUBBLE_MAX_WIDTH_DP.dp)
        assertEquals(BUBBLE_MAX_WIDTH_DP.dp, width)
    }

    @Test
    fun `horizontal chrome padding composes without insets and leaves the content in place`() {
        compose.setContent {
            Box(Modifier.horizontalChromePadding().fillMaxWidth().height(10.dp).testTag("page"))
        }
        val bounds = compose.onNodeWithTag("page").fetchSemanticsNode().boundsInRoot
        assertEquals(0f, bounds.left, 1f)
    }

    @Test
    fun `the onboarding bottom bar is shown and its buttons click in landscape`() {
        var next = 0
        var back = 0
        compose.setContent {
            UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
                BottomBar(
                    page = 1, pageCount = 4, nextLabel = "Next", reduceMotion = true,
                    onBack = { back++ }, onNext = { next++ },
                )
            }
        }
        compose.onNodeWithText("Next").assertIsDisplayed().performClick()
        compose.onNodeWithText("Back").assertIsDisplayed().performClick()
        assertEquals(1, next)
        assertEquals(1, back)
    }
}
