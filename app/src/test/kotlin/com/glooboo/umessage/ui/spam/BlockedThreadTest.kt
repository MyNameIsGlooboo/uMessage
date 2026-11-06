package com.glooboo.umessage.ui.spam

import android.app.Application
import android.os.Looper
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneOffset

/** The sender thread opened from the Blocked tab (owner notes r3). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h2000dp")
class BlockedThreadTest {

    @get:Rule
    val compose = createComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun str(id: Int, vararg args: Any) = app.getString(id, *args)

    private fun message(
        id: Long,
        at: Long,
        body: String = "text $id",
        reason: DecisionReason = DecisionReason.UNKNOWN_SENDER,
    ) = BlockedMessage(id, SENDER, body, 1, false, at, reason)

    @Test
    fun eachDayHeaderComesAfterItsMessagesForTheBottomUpList() {
        val day1 = LocalDate.of(2026, 11, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val day2 = day1 + DAY_MS
        val items = blockedThreadItems(
            listOf(message(1, day1 + 1000), message(3, day2 + 2000), message(2, day2 + 1000)),
            ZoneOffset.UTC,
        )
        assertThat(items.map { it.key }).containsExactly(
            "msg-3", "msg-2", "day-${LocalDate.of(2026, 11, 2).toEpochDay()}",
            "msg-1", "day-${LocalDate.of(2026, 11, 1).toEpochDay()}",
        ).inOrder()
    }

    @Test
    fun theThreadModelShowsEverySenderMessageWhateverTheTabsChip() {
        val now = System.currentTimeMillis()
        val repo = FakeSpamRepository(
            initialBlocked = listOf(
                message(1, now - 1000),
                message(2, now - 2000, reason = DecisionReason.BLOCK_RULE),
            ),
            initialRules = emptyList(),
        )
        val scope = CoroutineScope(Job() + Dispatchers.Main)
        try {
            val tab = BlockedViewModel(app, repo, RestoredLog(prefs = null))
            scope.launch { tab.state.collect { } }
            awaitUntil { !tab.state.value.loading }
            tab.setFilter(BlockedFilter.MyRules)
            awaitUntil { tab.state.value.groups.singleOrNull()?.messages?.size == 1 }

            val thread = BlockedViewModel(app, repo, RestoredLog(prefs = null), ignoreFilter = true)
            scope.launch { thread.state.collect { } }
            awaitUntil { !thread.state.value.loading }
            assertThat(thread.state.value.filter).isEqualTo(BlockedFilter.All)
            assertThat(thread.state.value.groups.single().messages.map { it.id }).containsExactly(1L, 2L)
            thread.setFilter(BlockedFilter.HasLink)
            assertThat(thread.state.value.groups.single().messages).hasSize(2)
            tab.setFilter(BlockedFilter.All)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun theThreadShowsTheMessagesAndOffersMoveToInbox() {
        val now = System.currentTimeMillis()
        val state = BlockedUiState(
            loading = false,
            groups = groupBySender(listOf(message(1, now, "newest text"), message(2, now - 60_000, "older text"))),
        )
        var moved = false
        compose.setContent {
            UMessageTheme {
                BlockedThreadScreen(
                    SENDER,
                    state,
                    SnackbarHostState(),
                    BlockedThreadActions(onMoveToInbox = { moved = true }),
                )
            }
        }
        compose.onNodeWithText("newest text", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("older text", useUnmergedTree = true).assertExists()
        compose.onNodeWithText(str(R.string.r3b_thread_note)).assertExists()
        compose.onNodeWithText(str(R.string.ux8c_not_spam), useUnmergedTree = true).performClick()
        assertThat(moved).isTrue()
    }

    @Test
    fun theThreadSaysWhyOnceAndOffersAVisibleDelete() {
        val now = System.currentTimeMillis()
        val state = BlockedUiState(
            loading = false,
            groups = groupBySender(
                listOf(
                    message(1, now, "newest text"),
                    message(2, now - 60_000, "older text"),
                    message(3, now - 120_000, "see https://example.com/x", DecisionReason.UNKNOWN_SENDER),
                ),
            ),
        )
        var deleted = false
        compose.setContent {
            UMessageTheme {
                BlockedThreadScreen(
                    SENDER, state, SnackbarHostState(), BlockedThreadActions(onDeleteAll = { deleted = true }),
                )
            }
        }
        compose.onAllNodesWithText(str(R.string.ux8c_held_unknown_link)).assertCountEquals(1)
        compose.onAllNodesWithText(str(R.string.ux8c_held_unknown)).assertCountEquals(1)
        compose.onNodeWithText(str(R.string.ux8c_delete), useUnmergedTree = true).performClick()
        assertThat(deleted).isTrue()
    }

    @Test
    fun theHeldLineFollowsTheReasonOfEachMessage() {
        fun line(body: String, reason: DecisionReason) = heldLineText(message(1, 0, body, reason))
        assertThat(line("hi", DecisionReason.UNKNOWN_SENDER)).isEqualTo(R.string.ux8c_held_unknown)
        assertThat(line("win at example.com", DecisionReason.UNKNOWN_SENDER)).isEqualTo(R.string.ux8c_held_unknown_link)
        assertThat(line("hi", DecisionReason.BLOCK_RULE)).isEqualTo(R.string.ux8c_held_rule)
        assertThat(line("hi", DecisionReason.FOREIGN_NUMBER)).isEqualTo(R.string.ux8c_held_foreign)
        assertThat(line("hi", DecisionReason.MODE_OFF)).isEqualTo(R.string.ux8c_held_other)
    }

    @Test
    fun aSenderWithNothingLeftShowsTheEmptyStateWithoutActions() {
        compose.setContent {
            UMessageTheme {
                BlockedThreadScreen(
                    SENDER,
                    BlockedUiState(loading = false),
                    SnackbarHostState(),
                    BlockedThreadActions(),
                )
            }
        }
        compose.onNodeWithText(str(R.string.r3b_thread_empty_title)).assertExists()
        compose.onNodeWithText(str(R.string.r3b_thread_note)).assertDoesNotExist()
    }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            check(System.currentTimeMillis() < deadline) { "condition not met" }
            Thread.sleep(10)
        }
    }

    private companion object {
        const val SENDER = "+447121234567"
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
