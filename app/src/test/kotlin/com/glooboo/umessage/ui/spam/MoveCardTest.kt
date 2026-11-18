package com.glooboo.umessage.ui.spam

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.ui.conversations.MarkReadProgress
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MoveCardTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun theSpokenCountMovesInQuartersOfTheTotal() {
        assertThat(spokenDone(0, 120)).isEqualTo(0)
        assertThat(spokenDone(29, 120)).isEqualTo(0)
        assertThat(spokenDone(30, 120)).isEqualTo(30)
        assertThat(spokenDone(119, 120)).isEqualTo(90)
        assertThat(spokenDone(120, 120)).isEqualTo(120)
        assertThat(spokenDone(5, 0)).isEqualTo(0)
        assertThat(spokenDone(0, 3)).isEqualTo(0)
        assertThat(spokenDone(2, 3)).isEqualTo(1)
        assertThat(spokenDone(3, 3)).isEqualTo(3)
    }

    @Test
    fun theIndicatorWaitsOutTheRestOfThe300Ms() {
        assertThat(moveIndicatorWait(startedAt = 1_000L, now = 1_000L)).isEqualTo(MOVE_INDICATOR_DELAY_MS)
        assertThat(moveIndicatorWait(1_000L, 1_299L)).isEqualTo(1L)
        assertThat(moveIndicatorWait(1_000L, 1_300L)).isEqualTo(0L)
        assertThat(moveIndicatorWait(1_000L, 9_000L)).isEqualTo(0L)
        // A clock that went backwards never waits longer than the delay.
        assertThat(moveIndicatorWait(1_000L, 500L)).isEqualTo(MOVE_INDICATOR_DELAY_MS)
    }

    @Test
    fun theCardShowsTheCountsOnceDueAndLeavesWithTheProgress() {
        var progress by mutableStateOf<MarkReadProgress?>(MarkReadProgress(12, 80))
        compose.setContent {
            MaterialTheme { MoveCardHost(progress, startedAt = LONG_AGO) }
        }

        compose.onNodeWithTag(MOVE_CARD_TAG).assertIsDisplayed()
        compose.onNodeWithText("Moving messages: 12 of 80", useUnmergedTree = true).assertIsDisplayed()

        progress = null
        compose.waitUntil { compose.onAllNodesWithTag(MOVE_CARD_TAG).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun nothingIsComposedWithoutProgressOrBeforeTheIndicatorIsDue() {
        compose.setContent {
            MaterialTheme {
                MoveCardHost(null, startedAt = LONG_AGO)
                MoveCardHost(MarkReadProgress(1, 2), startedAt = 0L)
            }
        }
        compose.onNodeWithTag(MOVE_CARD_TAG).assertDoesNotExist()
    }

    private companion object {
        const val LONG_AGO = 1L
    }
}
