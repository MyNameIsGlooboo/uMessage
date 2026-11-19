package com.glooboo.umessage.ui.main

import android.app.Application
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.testing.ProviderTruthFlagRule
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Provider truth (4.7, D2 A, G-4): the progress and "What changed" pages follow the first-run state alone, so a killed
 * app that comes back with the same state shows the same page. Only update and fresh-install passes get the full page.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class FirstRunPagesTest {

    @get:Rule
    val flag = ProviderTruthFlagRule(enabled = true)

    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val res = app.resources
    private val start = 1_700_000_000_000L

    private val update = FirstRunProgress(
        running = true,
        passKind = FirstRunPassKind.UPDATE,
        phase = FirstRunPhase.HISTORY,
        startedAtMs = start,
    )

    @After
    fun tearDown() = FirstRunPagesHost.reset()

    @Test
    fun onlyUpdateAndFreshInstallGetTheFullPage() {
        assertThat(FirstRunPagePolicy.pageFor(update, flagOn = true)).isEqualTo(FirstRunPage.PROGRESS)
        val fresh = update.copy(passKind = FirstRunPassKind.FRESH_INSTALL)
        assertThat(FirstRunPagePolicy.pageFor(fresh, flagOn = true)).isEqualTo(FirstRunPage.PROGRESS)
        // Switch on, contacts granted, restore, default again, a finished migration: the small bar only.
        val other = update.copy(passKind = FirstRunPassKind.OTHER)
        assertThat(FirstRunPagePolicy.pageFor(other, flagOn = true)).isEqualTo(FirstRunPage.BAR)
        assertThat(FirstRunPagePolicy.pageFor(update.copy(inBackground = true), flagOn = true))
            .isEqualTo(FirstRunPage.BAR)
    }

    @Test
    fun whatChangedComesAfterTheWorkAndCanWait() {
        val ended = FirstRunProgress(passKind = FirstRunPassKind.UPDATE, whatChanged = WhatChanged(12, 5))
        assertThat(FirstRunPagePolicy.pageFor(ended, flagOn = true)).isEqualTo(FirstRunPage.WHAT_CHANGED)
        assertThat(FirstRunPagePolicy.pageFor(ended, flagOn = true, deferWhatChanged = true))
            .isEqualTo(FirstRunPage.NONE)
        assertThat(FirstRunPagePolicy.pageFor(update.copy(whatChanged = WhatChanged(1, 1)), flagOn = true))
            .isEqualTo(FirstRunPage.PROGRESS)
        assertThat(FirstRunPagePolicy.pageFor(FirstRunProgress(), flagOn = true)).isEqualTo(FirstRunPage.NONE)
    }

    @Test
    fun nothingShowsWhileTheFlagIsOff() {
        assertThat(FirstRunPagePolicy.pageFor(update, flagOn = false)).isEqualTo(FirstRunPage.NONE)
        val ended = FirstRunProgress(whatChanged = WhatChanged(1, 1))
        assertThat(FirstRunPagePolicy.pageFor(ended, flagOn = false)).isEqualTo(FirstRunPage.NONE)
    }

    @Test
    fun continueInBackgroundComesAfterFifteenSecondsOnTheWallClock() {
        assertThat(FirstRunPagePolicy.showsBackgroundButton(update, nowMs = start + 14_999)).isFalse()
        assertThat(FirstRunPagePolicy.showsBackgroundButton(update, nowMs = start + 15_000)).isTrue()
        assertThat(FirstRunPagePolicy.msUntilBackgroundButton(update, nowMs = start + 5_000)).isEqualTo(10_000L)
        assertThat(FirstRunPagePolicy.showsBackgroundButton(update.copy(running = false), nowMs = start + 60_000))
            .isFalse()
    }

    @Test
    fun aStartInTheFutureOrAnUnknownStartNeverMakesTheButtonWait() {
        // The clock was set back, or the phone restarted with another time: counts as "15 s have passed".
        assertThat(FirstRunPagePolicy.showsBackgroundButton(update, nowMs = start - 3_600_000)).isTrue()
        assertThat(FirstRunPagePolicy.msUntilBackgroundButton(update, nowMs = start - 1)).isEqualTo(0L)
        assertThat(FirstRunPagePolicy.showsBackgroundButton(update.copy(startedAtMs = 0), nowMs = start)).isTrue()
        // A jump far ahead (a reboot a day later) shows it at once; the wait is never longer than 15 s.
        assertThat(FirstRunPagePolicy.showsBackgroundButton(update, nowMs = start + 86_400_000)).isTrue()
        assertThat(FirstRunPagePolicy.msUntilBackgroundButton(update, nowMs = start)).isAtMost(15_000L)
    }

    @Test
    fun stepsFollowThePhaseAndOlderSpamCanWait() {
        assertThat(FirstRunPagePolicy.stepState(update, FirstRunStep.RULES)).isEqualTo(FirstRunStepState.DONE)
        assertThat(FirstRunPagePolicy.stepState(update, FirstRunStep.OLDER_SPAM)).isEqualTo(FirstRunStepState.DONE)
        assertThat(FirstRunPagePolicy.stepState(update, FirstRunStep.HISTORY)).isEqualTo(FirstRunStepState.RUNNING)
        assertThat(FirstRunPagePolicy.stepState(update, FirstRunStep.SORT)).isEqualTo(FirstRunStepState.WAITING)
        assertThat(FirstRunPagePolicy.fraction(update)).isEqualTo(0.5f)

        val settings = update.copy(phase = FirstRunPhase.SETTINGS)
        assertThat(FirstRunPagePolicy.stepState(settings, FirstRunStep.RULES)).isEqualTo(FirstRunStepState.RUNNING)
        val cleanup = update.copy(phase = FirstRunPhase.CLEANUP)
        assertThat(FirstRunPagePolicy.stepState(cleanup, FirstRunStep.SORT)).isEqualTo(FirstRunStepState.DONE)

        val waiting = update.copy(olderSpamWaiting = true)
        assertThat(FirstRunPagePolicy.stepState(waiting, FirstRunStep.OLDER_SPAM))
            .isEqualTo(FirstRunStepState.DEFERRED)
        assertThat(FirstRunPassKind.FRESH_INSTALL.let { update.copy(passKind = it).steps })
            .doesNotContain(FirstRunStep.OLDER_SPAM)
    }

    @Test
    fun theProgressPageNamesTheStepsWithTheirStates() {
        val fresh = update.copy(passKind = FirstRunPassKind.FRESH_INSTALL, phase = FirstRunPhase.HISTORY)
        compose.setContent { UMessageTheme { FirstRunProgressPage(fresh, {}, now = { start }) } }
        compose.onNodeWithText(res.getString(R.string.pt_progress_title)).assertExists()
        compose.onNodeWithText(res.getString(R.string.pt_progress_body)).assertExists()
        compose.onNodeWithText(res.getString(R.string.pt_progress_step_older)).assertDoesNotExist()
        compose.onNode(hasState(res.getString(R.string.w3c_checklist_step_done))).assertExists()
        compose.onNode(hasState(res.getString(R.string.pt_progress_step_running))).assertExists()
        compose.onNode(hasState(res.getString(R.string.oem2_state_todo))).assertExists()
        compose.onNodeWithTag(FIRST_RUN_BACKGROUND_TAG).assertDoesNotExist()
    }

    @Test
    fun olderSpamThatWaitsSaysSo() {
        val waiting = update.copy(olderSpamWaiting = true)
        compose.setContent { UMessageTheme { FirstRunProgressPage(waiting, {}, now = { start }) } }
        compose.onNodeWithText(res.getString(R.string.pt_progress_older_waiting), useUnmergedTree = true).assertExists()
    }

    @Test
    fun theBackgroundButtonShowsOnceTheTimeHasPassed() {
        var tapped = false
        compose.setContent {
            UMessageTheme { FirstRunProgressPage(update, { tapped = true }, now = { start + 20_000 }) }
        }
        compose.onNodeWithTag(FIRST_RUN_BACKGROUND_TAG).performClick()
        assertThat(tapped).isTrue()
    }

    @Test
    fun whatChangedHasTheSixLinesAndBothButtons() {
        val lines = FirstRunPagePolicy.whatChangedLines(res, WhatChanged(chatsMoved = 12, sendersAllowed = 1))
        assertThat(lines).hasSize(6)
        assertThat(lines[0]).isEqualTo(res.getQuantityString(R.plurals.pt_changed_moved, 12, 12))
        assertThat(lines[1]).isEqualTo(res.getQuantityString(R.plurals.pt_changed_allowed, 1, 1))

        var dismissed = 0
        var reviewed = 0
        compose.setContent {
            UMessageTheme {
                FirstRunWhatChangedPage(WhatChanged(12, 1), onDismiss = { dismissed++ }, onReviewSpam = { reviewed++ })
            }
        }
        compose.onNodeWithText(res.getString(R.string.pt_changed_title)).assertExists()
        compose.onNodeWithText(res.getString(R.string.ux8c_tip_dismiss)).performClick()
        compose.onNodeWithTag(WHAT_CHANGED_REVIEW_TAG).performClick()
        assertThat(dismissed).isEqualTo(1)
        assertThat(reviewed).isEqualTo(1)
    }

    // region The host of MainActivity

    private class FakeSource(initial: FirstRunProgress) : FirstRunProgressSource {
        val state = MutableStateFlow(initial)
        var dismissed = 0
        override val progress: StateFlow<FirstRunProgress> = state
        override fun continueInBackground() = state.update { it.copy(inBackground = true) }
        override fun dismissWhatChanged() {
            dismissed++
            state.update { it.copy(whatChanged = null) }
        }
    }

    private var lastBar: Boolean? = null

    private fun host(source: FirstRunProgressSource?, onReview: () -> Unit = {}) {
        if (source != null) FirstRunPagesHost.install(source)
        compose.setContent {
            UMessageTheme {
                FirstRunHost(onReviewSpam = onReview, now = { start }) { bar ->
                    lastBar = bar != null
                    Text(MAIN)
                    bar?.invoke()
                }
            }
        }
    }

    @Test
    fun anUpdatePassReplacesTheMainScreen() {
        host(FakeSource(update))
        compose.onNodeWithText(res.getString(R.string.pt_progress_body)).assertExists()
        compose.onNodeWithText(MAIN).assertDoesNotExist()
    }

    @Test
    fun anOtherPassShowsTheMainScreenWithTheBar() {
        host(FakeSource(update.copy(passKind = FirstRunPassKind.OTHER)))
        compose.onNodeWithText(MAIN).assertExists()
        compose.onNodeWithText(res.getString(R.string.pt_progress_title)).assertExists()
        assertThat(lastBar).isTrue()
    }

    @Test
    fun aSourceInstalledLaterStillShowsItsPage() {
        host(source = null)
        compose.onNodeWithText(MAIN).assertExists()
        assertThat(lastBar).isFalse()
        FirstRunPagesHost.install(FakeSource(update))
        compose.waitForIdle()
        compose.onNodeWithText(res.getString(R.string.pt_progress_body)).assertExists()
    }

    @Test
    fun whatChangedWaitsWhenTheUserWentOnInTheBackground() {
        val source = FakeSource(update.copy(inBackground = true))
        host(source)
        compose.onNodeWithText(MAIN).assertExists()
        // The work ends while the app is in use: no page jumps in.
        source.state.value = FirstRunProgress(passKind = FirstRunPassKind.UPDATE, whatChanged = WhatChanged(3, 1))
        compose.waitForIdle()
        compose.onNodeWithText(MAIN).assertExists()
        compose.onNodeWithText(res.getString(R.string.pt_changed_title)).assertDoesNotExist()
    }

    @Test
    fun whatChangedShowsAtOnceWhenTheUserStayedOnTheProgressPage() {
        var reviewed = 0
        val source = FakeSource(update)
        host(source, onReview = { reviewed++ })
        source.state.value = FirstRunProgress(passKind = FirstRunPassKind.UPDATE, whatChanged = WhatChanged(3, 1))
        compose.waitForIdle()
        compose.onNodeWithTag(WHAT_CHANGED_REVIEW_TAG).performClick()
        assertThat(reviewed).isEqualTo(1)
        assertThat(source.dismissed).isEqualTo(1)
        compose.onNodeWithText(MAIN).assertExists()
    }

    // endregion

    private fun hasState(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text)

    private companion object {
        const val MAIN = "main screen"
    }
}
