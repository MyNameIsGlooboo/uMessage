package com.glooboo.umessage.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.ui.theme.UMessageTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The welcome card on a small phone with a large font: it shows, it scrolls, and "Get started" stays on screen. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h640dp")
class WelcomeAnimationTest {
    @get:Rule
    val compose = createComposeRule()

    private val description = "Texts from people you know"
    private val total = WelcomeTimeline.TOTAL_MS.toLong()

    @Composable
    private fun Page(reduceMotion: Boolean, direction: LayoutDirection = LayoutDirection.Ltr) {
        CompositionLocalProvider(
            LocalDensity provides Density(density = 1f, fontScale = 2f),
            LocalLayoutDirection provides direction,
        ) {
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

    private fun setUp(reduceMotion: Boolean, direction: LayoutDirection = LayoutDirection.Ltr) =
        compose.setContent { Page(reduceMotion, direction) }

    private fun assertCardAndButtonShow() {
        compose.onNodeWithContentDescription(description, substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Get started").assertIsDisplayed()
    }

    @Test
    fun `with reduced motion the card shows at once and the clock never runs`() {
        compose.mainClock.autoAdvance = false
        setUp(reduceMotion = true)
        assertCardAndButtonShow()
    }

    @Test
    fun `with motion on the card plays and the button is still on screen`() {
        setUp(reduceMotion = false)
        compose.mainClock.advanceTimeBy(total + 100L)
        assertCardAndButtonShow()
    }

    @Test
    @Config(qualifiers = "w320dp-h568dp")
    fun `on a narrow screen with the largest font the card is stacked, scrolls into view, and the button stays`() {
        setUp(reduceMotion = true)
        assertCardAndButtonShow()
    }

    @Test
    fun `in right-to-left the page composes and the card and the button show`() {
        setUp(reduceMotion = true, direction = LayoutDirection.Rtl)
        assertCardAndButtonShow()
    }

    @Test
    fun `a tap on the card replays it with motion on, without a crash`() {
        compose.mainClock.autoAdvance = false
        setUp(reduceMotion = false)
        compose.mainClock.advanceTimeBy(total + 100L)
        compose.onNodeWithContentDescription(description, substring = true).performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(REPLAY_WAIT_MS)
        compose.onNodeWithContentDescription(description, substring = true).assertIsDisplayed()
    }

    @Test
    fun `a tap on the card does nothing with reduced motion`() {
        compose.mainClock.autoAdvance = false
        setUp(reduceMotion = true)
        compose.onNodeWithContentDescription(description, substring = true).performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(REPLAY_WAIT_MS)
        compose.onNodeWithContentDescription(description, substring = true).assertIsDisplayed()
    }

    @Test
    fun `after a rotation the card is still there and starts on its final frame`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Page(reduceMotion = false) }
        compose.mainClock.advanceTimeBy(total + 100L)
        restoration.emulateSavedInstanceStateRestore()
        assertCardAndButtonShow()
    }

    private companion object {
        const val REPLAY_WAIT_MS = 5000L
    }
}
