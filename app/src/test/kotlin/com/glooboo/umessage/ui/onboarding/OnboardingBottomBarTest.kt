package com.glooboo.umessage.ui.onboarding

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.ui.theme.UMessageTheme
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** UI-bigfont: the main button keeps its whole label at a large font scale on a small phone. */
@RunWith(AndroidJUnit4::class)
// Native graphics: the default legacy mode measures text with made-up widths, which says nothing about a label
// that is cut at a large font.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w320dp-h640dp")
class OnboardingBottomBarTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `at twice the font size the Next label is not cut and the bar still shows Back`() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
                    BottomBar(
                        page = 3, pageCount = 4, nextLabel = "Start messaging", reduceMotion = true,
                        onBack = {}, onNext = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Back").assertIsDisplayed()
        val node = compose.onNodeWithText("Start messaging")
        node.assertIsDisplayed()
        val results = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        assertFalse("the label is cut by an ellipsis", results.first().hasVisualOverflow)
    }
}
