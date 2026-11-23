package com.glooboo.umessage.ui.spam

import android.app.Application
import android.os.Looper
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.activities.ThreadActivity
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.ui.conversations.MarkReadProgress
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
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

    @Test
    fun whileMovingTheBubblesStayTheFooterShowsTheProgressAndBothButtonsWait() {
        val now = System.currentTimeMillis()
        val group = groupBySender(
            listOf(message(1, now, "newest text"), message(2, now - 60_000, "older text")),
        ).single()
        // The messages already left the held list: only the shared move state still knows them.
        val state = BlockedUiState(
            loading = false,
            movingGroups = listOf(group),
            moveProgress = MarkReadProgress(1, 2),
            moveStartedAt = 1L,
        )
        compose.setContent {
            UMessageTheme {
                BlockedThreadScreen(
                    SENDER, state, SnackbarHostState(), BlockedThreadActions(), moving = true, movingGroup = group,
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("newest text", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("older text", useUnmergedTree = true).assertExists()
        compose.onNodeWithText(str(R.string.move_progress, "1", "2"), useUnmergedTree = true).assertExists()
        compose.onNodeWithText(str(R.string.r3b_thread_note)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.r3b_thread_empty_title)).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.ux8c_not_spam)).assertIsNotEnabled()
        compose.onNodeWithText(str(R.string.ux8c_delete)).assertIsNotEnabled()
    }

    @Test
    fun whenNothingMovesTheFooterShowsTheNoteAndNoProgress() {
        val now = System.currentTimeMillis()
        val state = BlockedUiState(
            loading = false,
            groups = groupBySender(listOf(message(1, now, "newest text"))),
            moveProgress = MarkReadProgress(1, 2),
            moveStartedAt = 1L,
        )
        compose.setContent {
            UMessageTheme {
                BlockedThreadScreen(SENDER, state, SnackbarHostState(), BlockedThreadActions(), moving = false)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.r3b_thread_note)).assertExists()
        compose.onNodeWithText(str(R.string.move_progress, "1", "2"), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun movingMessagesAndHeldOnesShareOnePageWithoutDoubles() {
        val now = System.currentTimeMillis()
        val moving = groupBySender(listOf(message(1, now, "a"), message(2, now - 1_000, "b"))).single()
        val live = groupBySender(listOf(message(2, now - 1_000, "b"), message(3, now - 2_000, "c"))).single()
        assertThat(threadMessages(live, moving).map { it.id }).containsExactly(1L, 2L, 3L).inOrder()
        assertThat(threadMessages(null, moving).map { it.id }).containsExactly(1L, 2L).inOrder()
        assertThat(threadMessages(null, null)).isEmpty()
    }

    @Test
    fun messagesOfTheSameTimeKeepOneOrderByIdNewestFirst() {
        val at = 1_700_000_000_000L
        val moving = groupBySender(listOf(message(1, at), message(3, at))).single()
        val live = groupBySender(listOf(message(2, at), message(3, at))).single()
        assertThat(threadMessages(live, moving).map { it.id }).containsExactly(3L, 2L, 1L).inOrder()
        assertThat(threadMessages(moving, live).map { it.id }).containsExactly(3L, 2L, 1L).inOrder()
    }

    private fun routeModel(repo: FakeSpamRepository, moves: NotSpamMoves = NotSpamMoves()) =
        BlockedViewModel(app, repo, RestoredLog(prefs = null), ignoreFilter = true, moves = moves)

    private fun showRoute(
        vm: BlockedViewModel,
        onBack: () -> Unit,
        threadIdOf: suspend (android.content.Context, String) -> Long = { _, _ -> CHAT_ID },
    ) = compose.setContent {
        UMessageTheme { BlockedThreadRoute(SENDER, onBack, onOpenRules = {}, viewModel = vm, threadIdOf = threadIdOf) }
    }

    /** Runs the main looper and the compose clock forward, in small steps, until [done]. */
    private fun settle(done: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!done()) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(STEP_MS))
            compose.waitForIdle()
            check(System.currentTimeMillis() < deadline) { "condition not met" }
            Thread.sleep(10)
        }
    }

    private fun settleFor(ms: Long) {
        var left = ms
        while (left > 0) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(STEP_MS))
            compose.waitForIdle()
            left -= STEP_MS
        }
    }

    private fun chatsOpened() =
        generateSequence { shadowOf(app).nextStartedActivity }
            .count { it.component?.className == ThreadActivity::class.java.name }

    @Test
    fun anOwnNotSpamOpensTheChatEvenWhenTheLookUpIsSlowAndDoesNotCloseEarly() {
        val repo = FakeSpamRepository(initialBlocked = listOf(message(1, 0)), initialRules = emptyList())
        val lookUp = CompletableDeferred<Long>()
        var closed = 0
        showRoute(routeModel(repo), { closed++ }) { _, _ -> lookUp.await() }
        compose.onNodeWithText(str(R.string.ux8c_not_spam), useUnmergedTree = true).performClick()
        settle { runBlocking { repo.blocked.first() }.isEmpty() }
        // The move is over, the chat is not open yet: the page must wait for it, well past its own close grace.
        settleFor(1_000)
        assertThat(closed).isEqualTo(0)
        lookUp.complete(CHAT_ID)
        settle { closed > 0 }
        assertThat(chatsOpened()).isEqualTo(1)
        assertThat(closed).isEqualTo(1)
    }

    @Test
    fun aMovingFlagLeftOverFromProcessDeathIsClearedSoTheButtonsWorkAgain() {
        val tester = StateRestorationTester(compose)
        var vm = routeModel(FakeSpamRepository(initialBlocked = listOf(message(1, 0)), initialRules = emptyList()))
        tester.setContent { UMessageTheme { BlockedThreadRoute(SENDER, {}, {}, vm, { _, _ -> 0L }) } }
        compose.onNodeWithText(str(R.string.ux8c_not_spam), useUnmergedTree = true).performClick()
        // The process dies: the page is rebuilt from its saved state with a new model that moves nothing.
        vm = routeModel(FakeSpamRepository(initialBlocked = listOf(message(1, 0)), initialRules = emptyList()))
        tester.emulateSavedInstanceStateRestore()
        settle { runCatching { compose.onNodeWithText(str(R.string.ux8c_not_spam)).assertIsEnabled() }.isSuccess }
    }

    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            check(System.currentTimeMillis() < deadline) { "condition not met" }
            Thread.sleep(10)
        }
    }

    private companion object {
        const val SENDER = "+15550001234"
        const val CHAT_ID = 42L
        const val STEP_MS = 50L
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
