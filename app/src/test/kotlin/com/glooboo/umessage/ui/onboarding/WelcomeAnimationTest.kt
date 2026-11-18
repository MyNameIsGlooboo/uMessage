package com.glooboo.umessage.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.ui.theme.UMessageTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The welcome picture on a small phone with a large font: it shows, and "Get started" stays on screen. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp")
class WelcomeAnimationTest {
    @get:Rule
    val compose = createComposeRule()

    private fun setUp(reduceMotion: Boolean) = compose.setContent {
        CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
            UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
                Column(Modifier.fillMaxWidth()) {
                    WelcomePage(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        reduceMotion = reduceMotion,
                    )
                    BottomBar(
                        page = 0, pageCount = 4, nextLabel = "Get started", reduceMotion = reduceMotion,
                        onBack = {}, onNext = {},
                    )
                }
            }
        }
    }

    @Test
    fun `with reduced motion the picture shows at once and the clock never runs`() {
        compose.mainClock.autoAdvance = false
        setUp(reduceMotion = true)
        compose.onNodeWithContentDescription("Texts from people you know", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Get started").assertIsDisplayed()
    }

    @Test
    fun `with motion on the picture plays and the button is still on screen`() {
        setUp(reduceMotion = false)
        compose.mainClock.advanceTimeBy(WelcomeTimeline.TOTAL_MS + 100L)
        compose.onNodeWithContentDescription("Texts from people you know", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Get started").assertIsDisplayed()
    }
}
