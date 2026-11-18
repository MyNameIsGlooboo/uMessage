package com.glooboo.umessage.ui.onboarding

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.UnknownSenderMode
import com.glooboo.umessage.ui.theme.UMessageTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun `with reduced motion the card shows at once`() {
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
    fun `a tap on the card with reduced motion leaves the card showing`() {
        compose.mainClock.autoAdvance = false
        setUp(reduceMotion = true)
        compose.onNodeWithContentDescription(description, substring = true).performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(REPLAY_WAIT_MS)
        assertCardAndButtonShow()
    }

    @Test
    fun `after a rotation the card is still there and starts on its final frame`() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Page(reduceMotion = false) }
        compose.mainClock.advanceTimeBy(total + 100L)
        restoration.emulateSavedInstanceStateRestore()
        assertCardAndButtonShow()
    }

    private fun showCard(
        mode: UnknownSenderMode,
        autoPlay: Boolean = false,
        reduceMotion: Boolean = true,
    ) = compose.setContent {
        UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
            SortingCard(mode = mode, autoPlay = autoPlay, reduceMotion = reduceMotion)
        }
    }

    @Test
    fun `in Strict the card shows Spam twice and Messages once, without the Smart sentence`() {
        showCard(UnknownSenderMode.STRICT)
        compose.onAllNodesWithText("Spam").assertCountEquals(2)
        compose.onAllNodesWithText("Messages").assertCountEquals(1)
        compose.onAllNodesWithContentDescription(description, substring = true).assertCountEquals(0)
    }

    @Test
    fun `in Off the card shows Messages three times and no Spam`() {
        showCard(UnknownSenderMode.OFF)
        compose.onAllNodesWithText("Messages").assertCountEquals(3)
        compose.onAllNodesWithText("Spam").assertCountEquals(0)
    }

    @Test
    fun `the card plays only when motion is on and it has not played yet`() {
        assertTrue(!WelcomeTimeline.startsFinished(reduceMotion = false, alreadyPlayed = false))
        assertTrue(WelcomeTimeline.startsFinished(reduceMotion = true, alreadyPlayed = false))
        assertTrue(WelcomeTimeline.startsFinished(reduceMotion = false, alreadyPlayed = true))
    }

    @Test
    fun `a replay from its start point still runs past every row`() {
        val left = WelcomeTimeline.TOTAL_MS - WelcomeTimeline.REPLAY_FROM_MS
        assertTrue(left >= WelcomeTimeline.rows.size * WelcomeTimeline.ROW_MS)
        assertEquals(0.3f, WelcomeTimeline.senderAlpha(0, WelcomeTimeline.REPLAY_FROM_MS.toFloat()), 0f)
    }

    @Test
    fun `the final frame has every part lit at its normal size`() {
        for (index in WelcomeTimeline.rows.indices) {
            val end = WelcomeTimeline.TOTAL_MS.toFloat()
            assertEquals(1f, WelcomeTimeline.senderAlpha(index, end), 0f)
            assertEquals(1f, WelcomeTimeline.arrowAlpha(index, end), 0f)
            assertEquals(1f, WelcomeTimeline.pillAlpha(index, end), 0f)
            assertEquals(1f, WelcomeTimeline.pillScale(index, end), 0f)
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp")
    fun `at normal font size and 360 dp the card keeps each row on one line`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
                UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
                    SortingCard(mode = UnknownSenderMode.STRICT, autoPlay = false, reduceMotion = true)
                }
            }
        }
        val label = compose.onNodeWithText(context.getString(R.string.onb_perm_contacts), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val pill = compose.onNodeWithText(context.getString(R.string.main_tab_messages), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("label and destination share a line", pill.top < label.bottom && label.top < pill.bottom)
    }

    @Test
    fun `a tap on a Strict card with reduced motion leaves the final frame`() {
        compose.mainClock.autoAdvance = false
        showCard(UnknownSenderMode.STRICT)
        compose.onAllNodesWithText("Spam").onFirst().performClick()
        compose.mainClock.advanceTimeBy(REPLAY_WAIT_MS)
        compose.onAllNodesWithText("Spam").assertCountEquals(2)
    }

    private fun tapCount(enabled: Boolean, ripple: Boolean): Int {
        var taps = 0
        compose.setContent {
            Box(Modifier.size(40.dp).testTag("tap").replayable(enabled, ripple) { taps++ })
        }
        compose.onNodeWithTag("tap").performClick()
        return taps
    }

    @Test
    fun `replayable with enabled false ignores a click`() {
        assertEquals(0, tapCount(enabled = false, ripple = true))
    }

    @Test
    fun `replayable with enabled false ignores a plain gesture tap`() {
        assertEquals(0, tapCount(enabled = false, ripple = false))
    }

    @Test
    fun `replayable with enabled true replays on a click`() {
        assertEquals(1, tapCount(enabled = true, ripple = true))
    }

    @Test
    fun `replayable with enabled true replays on a plain gesture tap`() {
        assertEquals(1, tapCount(enabled = true, ripple = false))
    }

    private companion object {
        const val REPLAY_WAIT_MS = 5000L
    }
}
