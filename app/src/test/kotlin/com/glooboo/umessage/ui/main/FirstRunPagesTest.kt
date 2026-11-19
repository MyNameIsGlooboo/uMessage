package com.glooboo.umessage.ui.main

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Provider truth (4.7, D2 A): the progress and "What changed" pages follow the first-run state alone, so a killed app
 * that comes back with the same state shows the same page.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class FirstRunPagesTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val res = app.resources

    private val running = FirstRunProgress(
        running = true,
        current = FirstRunStep.HISTORY,
        done = setOf(FirstRunStep.RULES, FirstRunStep.OLDER_SPAM),
        startedAtMs = 100_000L,
    )

    @Test
    fun thePageFollowsTheState() {
        assertThat(FirstRunPagePolicy.pageFor(FirstRunProgress(), flagOn = true)).isEqualTo(FirstRunPage.NONE)
        assertThat(FirstRunPagePolicy.pageFor(running, flagOn = true)).isEqualTo(FirstRunPage.PROGRESS)
        assertThat(FirstRunPagePolicy.pageFor(running.copy(inBackground = true), flagOn = true))
            .isEqualTo(FirstRunPage.BAR)
        val ended = FirstRunProgress(whatChanged = WhatChanged(chatsMoved = 12, sendersAllowed = 5))
        assertThat(FirstRunPagePolicy.pageFor(ended, flagOn = true)).isEqualTo(FirstRunPage.WHAT_CHANGED)
        // The work goes on first: "What changed" waits until it has ended.
        assertThat(FirstRunPagePolicy.pageFor(running.copy(whatChanged = ended.whatChanged), flagOn = true))
            .isEqualTo(FirstRunPage.PROGRESS)
    }

    @Test
    fun nothingShowsWhileTheFlagIsOff() {
        assertThat(FirstRunPagePolicy.pageFor(running, flagOn = false)).isEqualTo(FirstRunPage.NONE)
        val ended = FirstRunProgress(whatChanged = WhatChanged(1, 1))
        assertThat(FirstRunPagePolicy.pageFor(ended, flagOn = false)).isEqualTo(FirstRunPage.NONE)
    }

    @Test
    fun continueInBackgroundComesAfterFifteenSecondsAlsoAfterARestart() {
        assertThat(FirstRunPagePolicy.showsBackgroundButton(running, nowMs = 100_000L + 14_999)).isFalse()
        assertThat(FirstRunPagePolicy.showsBackgroundButton(running, nowMs = 100_000L + 15_000)).isTrue()
        assertThat(FirstRunPagePolicy.msUntilBackgroundButton(running, nowMs = 100_000L + 5_000)).isEqualTo(10_000L)
        assertThat(FirstRunPagePolicy.showsBackgroundButton(running.copy(running = false), nowMs = 1_000_000L))
            .isFalse()
    }

    @Test
    fun eachStepHasItsState() {
        assertThat(FirstRunPagePolicy.stepState(running, FirstRunStep.RULES)).isEqualTo(FirstRunStepState.DONE)
        assertThat(FirstRunPagePolicy.stepState(running, FirstRunStep.HISTORY)).isEqualTo(FirstRunStepState.RUNNING)
        assertThat(FirstRunPagePolicy.stepState(running, FirstRunStep.SORT)).isEqualTo(FirstRunStepState.WAITING)
        assertThat(FirstRunPagePolicy.fraction(running)).isEqualTo(0.5f)
    }

    @Test
    fun theProgressPageNamesTheStepsAndLeavesOutOlderSpamOnAFreshInstall() {
        val fresh = running.copy(
            steps = listOf(FirstRunStep.RULES, FirstRunStep.HISTORY, FirstRunStep.SORT),
            done = setOf(FirstRunStep.RULES),
        )
        compose.setContent { UMessageTheme { FirstRunProgressPage(fresh, {}, now = { 100_000L }) } }
        compose.onNodeWithText(res.getString(R.string.pt_progress_title)).assertExists()
        compose.onNodeWithText(res.getString(R.string.pt_progress_body)).assertExists()
        compose.onNodeWithText(res.getString(R.string.pt_progress_step_rules)).assertExists()
        compose.onNodeWithText(res.getString(R.string.pt_progress_step_history)).assertExists()
        compose.onNodeWithText(res.getString(R.string.pt_progress_step_sort)).assertExists()
        compose.onNodeWithText(res.getString(R.string.pt_progress_step_older)).assertDoesNotExist()
        compose.onNodeWithTag(FIRST_RUN_BACKGROUND_TAG).assertDoesNotExist()
    }

    @Test
    fun theBackgroundButtonShowsOnceTheTimeHasPassed() {
        var tapped = false
        compose.setContent {
            UMessageTheme { FirstRunProgressPage(running, { tapped = true }, now = { 100_000L + 20_000 }) }
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
        compose.onNodeWithText(res.getString(R.string.pt_changed_purge)).assertExists()
        compose.onNodeWithText(res.getString(R.string.ux8c_tip_dismiss)).performClick()
        compose.onNodeWithTag(WHAT_CHANGED_REVIEW_TAG).performClick()
        assertThat(dismissed).isEqualTo(1)
        assertThat(reviewed).isEqualTo(1)
    }

    @Test
    fun theRunnerIsNotSetYetSoNothingShows() {
        assertThat(FirstRunPagesHost.source.progress.value).isEqualTo(FirstRunProgress())
    }
}
