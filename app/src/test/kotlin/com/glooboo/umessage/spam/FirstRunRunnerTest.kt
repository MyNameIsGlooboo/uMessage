package com.glooboo.umessage.spam

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Step B, the first-run runner (3.5, S5, D2 A): the steps run in their fixed order, nothing moves before the sort, a
 * killed or failed pass resumes at its step, and the triggers make the passes the pages and the card follow.
 */
@RunWith(AndroidJUnit4::class)
class FirstRunRunnerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: FirstRunStore

    /** Records every step; each can be made to fail or to "kill" the app (throw) once. */
    private inner class Steps : FirstRunSteps {
        val calls = mutableListOf<String>()
        var failHistory = 0
        var failSort = 0
        var killAt: String? = null
        var migrationWaits = false
        var moved = 3
        var allowed = 2

        /** The table the lists read: written only by the sort. */
        var published = false

        /** Sorts that ran, kept when a test clears [calls]. */
        var sorts = 0

        /** The spam_since of every row the sorts wrote, and the added_at of every HISTORY member (B-12). */
        val table = mutableListOf<Long>()
        val historyMembers = mutableListOf<Long>()

        /** The sort commits its rows, then the app is killed before the runner saves (B-25). */
        var killAfterCommit = false
        private var committedBeforeKill = false

        /** B2-2: the store's chats, read at most once per pass; every read gets the next number. */
        private var storeReads = 0
        private var kept: Int? = null
        val historyRead = mutableListOf<Int>()
        val sortRead = mutableListOf<Int>()

        private fun storeChats(): Int = kept ?: (++storeReads).also { kept = it }

        override fun passStarted() {
            kept = null
        }

        private fun call(name: String) {
            if (killAt == name) {
                killAt = null
                error("killed")
            }
            calls += name
        }

        override fun applySettings() = call("settings")
        override fun migrateRules() = call("rules")
        override fun migrateOlderSpam(): Boolean {
            call("older")
            return !migrationWaits
        }

        override fun historyCheck(): HistoryResult? {
            call("history")
            // Nothing is published before the first pass's sort; a later pass checks again on a published table.
            if (sorts == 0) assertThat(published).isFalse()
            if (failHistory > 0) {
                failHistory--
                return null
            }
            repeat(allowed) { historyMembers += clock }
            historyRead += storeChats()
            return HistoryResult(wrote = allowed, code = 0)
        }

        override fun sort(): SortResult {
            call("sort")
            sorts++
            if (failSort > 0) {
                failSort--
                return SortResult.Failed("contacts")
            }
            published = true
            sortRead += storeChats()
            // A sort repeated after a kill finds its rows written already: it adds nothing (the real diff does so too).
            val added = if (committedBeforeKill) 0 else moved
            committedBeforeKill = false
            repeat(added) { table += clock }
            if (killAfterCommit) {
                killAfterCommit = false
                committedBeforeKill = true
                error("killed after the commit")
            }
            return SortResult.Applied(added = added, removed = 0, changed = 0)
        }

        override fun countMoved(since: Long): Int = table.count { it >= since }

        override fun countAllowed(since: Long): Int = historyMembers.count { it >= since }

        override fun cleanUps() {
            call("cleanup")
            assertThat(published).isTrue()
        }
    }

    private lateinit var steps: Steps
    private var clock = 5_000L
    private val seen = mutableListOf<FirstRunState>()
    private var publishes = 0

    private fun runner() = FirstRunRunner(store, steps, { clock }, { publishes++ }) { seen += it }

    @Before
    fun setUp() {
        val prefs = context.getSharedPreferences("first_run_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        store = FirstRunStore(prefs)
        steps = Steps()
    }

    @Test
    fun anUpdateRunsEveryStepInItsFixedOrder() {
        val state = runner().request(FirstRunTrigger.UPDATE)

        assertThat(steps.calls).containsExactly("settings", "rules", "older", "history", "sort", "cleanup").inOrder()
        assertThat(state.phase).isEqualTo(FirstRunPhase.DONE)
        assertThat(state.sortPublished).isTrue()
        assertThat(state.movedToSpam).isEqualTo(3)
        assertThat(state.allowedAutomatically).isEqualTo(2)
        assertThat(state.cardPending).isTrue()
        assertThat(state.whatChangedPending).isTrue()
        assertThat(state.existingUser).isTrue()
        assertThat(store.read()).isEqualTo(state)
    }

    @Test
    fun aFreshInstallShowsTheCardButNotWhatChanged() {
        val state = runner().request(FirstRunTrigger.FRESH_INSTALL)

        assertThat(state.cardPending).isTrue()
        assertThat(state.whatChangedPending).isFalse()
        assertThat(state.existingUser).isFalse()
    }

    @Test
    fun nothingMovesBeforeTheSortAndFullSortsWaitForThePass() {
        val states = mutableListOf<FirstRunState>()
        FirstRunRunner(store, steps, { clock }) { states += it }.request(FirstRunTrigger.UPDATE)

        // Every state saved before the sort step ended says "not published": the lists still ignore spam_threads.
        assertThat(states.filter { it.phase <= FirstRunPhase.SORT }.none { it.sortPublished }).isTrue()
        assertThat(states.filter { it.active }.none { it.allowsFullSort }).isTrue()
        assertThat(states.last().allowsFullSort).isTrue()
        // The history check is pending until it ran, so a text meanwhile is checked on the spot (R2).
        assertThat(FirstRunState().historyPending).isTrue()
        assertThat(states.first { it.phase == FirstRunPhase.HISTORY }.historyPending).isTrue()
        assertThat(states.first { it.phase == FirstRunPhase.SORT }.historyPending).isFalse()
        assertThat(states.last().historyPending).isFalse()
    }

    @Test
    fun aKilledPassResumesAtItsStepWithoutRepeatingTheOthers() {
        steps.killAt = "history"
        val stopped = runner().request(FirstRunTrigger.UPDATE)
        assertThat(stopped.phase).isEqualTo(FirstRunPhase.HISTORY)
        assertThat(stopped.active).isTrue()
        assertThat(stopped.sortPublished).isFalse()

        // A new process: a new runner over the same stored state.
        val resumed = runner().resume()

        assertThat(steps.calls).containsExactly("settings", "rules", "older", "history", "sort", "cleanup").inOrder()
        assertThat(resumed.phase).isEqualTo(FirstRunPhase.DONE)
    }

    @Test
    fun aKillDuringTheSortResumesTheSort() {
        steps.killAt = "sort"
        runner().request(FirstRunTrigger.UPDATE)
        assertThat(store.read().phase).isEqualTo(FirstRunPhase.SORT)

        runner().resume()

        assertThat(steps.calls.count { it == "history" }).isEqualTo(1)
        assertThat(steps.calls.count { it == "sort" }).isEqualTo(1)
        assertThat(store.read().phase).isEqualTo(FirstRunPhase.DONE)
    }

    @Test
    fun aFailedHistoryCheckStopsBeforeTheSort() {
        steps.failHistory = 1

        val stopped = runner().request(FirstRunTrigger.UPDATE)

        assertThat(stopped.phase).isEqualTo(FirstRunPhase.HISTORY)
        assertThat(steps.calls).doesNotContain("sort")
        assertThat(steps.published).isFalse()
        assertThat(runner().resume().phase).isEqualTo(FirstRunPhase.DONE)
    }

    @Test
    fun aFailedSortKeepsTheOldTableAndIsTriedAgain() {
        steps.failSort = 1

        val stopped = runner().request(FirstRunTrigger.UPDATE)

        assertThat(stopped.phase).isEqualTo(FirstRunPhase.SORT)
        assertThat(stopped.sortPublished).isFalse()
        assertThat(steps.calls).doesNotContain("cleanup")
        assertThat(runner().resume().sortPublished).isTrue()
    }

    @Test
    fun aWaitingMigrationDoesNotStopThePass() {
        steps.migrationWaits = true

        val state = runner().request(FirstRunTrigger.UPDATE)

        assertThat(state.phase).isEqualTo(FirstRunPhase.DONE)
        assertThat(state.olderSpamWaiting).isTrue()
    }

    @Test
    fun theSwitchGoingOnChecksTheHistoryAndSortsAgain() {
        runner().request(FirstRunTrigger.UPDATE)
        steps.calls.clear()
        store.write(store.read().copy(cardPending = false, movedToSpam = 0))
        clock = 6_000L

        val state = runner().request(FirstRunTrigger.SWITCH_ON)

        assertThat(steps.calls).containsExactly("history", "sort").inOrder()
        assertThat(state.cardPending).isTrue()
        assertThat(state.movedToSpam).isEqualTo(3)
    }

    @Test
    fun contactsGrantedLaterRunsTheHistoryCheckAndShowsTheCard() {
        // U3, R5: a fresh install without the contacts permission; it is granted later.
        runner().request(FirstRunTrigger.FRESH_INSTALL)
        store.write(store.read().copy(cardPending = false, movedToSpam = 0))
        steps.calls.clear()

        val state = runner().request(FirstRunTrigger.CONTACTS_GRANTED)

        assertThat(steps.calls).containsExactly("history", "sort").inOrder()
        assertThat(state.cardPending).isTrue()
    }

    @Test
    fun becomingTheDefaultAgainRunsTheWaitingMigrationFirstWithoutACard() {
        runner().request(FirstRunTrigger.UPDATE)
        store.write(store.read().copy(cardPending = false))
        steps.calls.clear()

        val state = runner().request(FirstRunTrigger.DEFAULT_AGAIN)

        assertThat(steps.calls).containsExactly("older", "history", "sort").inOrder()
        assertThat(state.cardPending).isFalse()
    }

    @Test
    fun aPartialTriggerBeforeTheFirstPassWaitsForIt() {
        val state = runner().request(FirstRunTrigger.SWITCH_ON)

        assertThat(state.phase).isEqualTo(FirstRunPhase.NOT_STARTED)
        assertThat(steps.calls).isEmpty()
    }

    @Test
    fun aTriggerDuringAPassMakesOneMorePassAfterIt() {
        steps.killAt = "history"
        runner().request(FirstRunTrigger.UPDATE)
        runner().request(FirstRunTrigger.CONTACTS_GRANTED)
        assertThat(store.pending).containsExactly(FirstRunTrigger.CONTACTS_GRANTED)

        val state = runner().resume()

        assertThat(steps.calls)
            .containsExactly("settings", "rules", "older", "history", "sort", "cleanup", "history", "sort").inOrder()
        assertThat(state.phase).isEqualTo(FirstRunPhase.DONE)
        assertThat(state.trigger).isEqualTo(FirstRunTrigger.CONTACTS_GRANTED)
        assertThat(store.pending).isEmpty()
        // The update's pages stay pending across the second pass.
        assertThat(state.whatChangedPending).isTrue()
        assertThat(state.movedToSpam).isEqualTo(6)
    }

    @Test
    fun aPassChainedFromAWaitingTriggerReadsTheStoresChatsAgain() {
        // B2-2: chats that reached the store during the first pass get the second pass's history check.
        steps.killAt = "history"
        runner().request(FirstRunTrigger.UPDATE)
        runner().request(FirstRunTrigger.NEW_CHATS)

        runner().resume()

        assertThat(steps.calls)
            .containsExactly("settings", "rules", "older", "history", "sort", "cleanup", "history", "sort").inOrder()
        // Each pass's history check and sort see the same read; the chained pass reads again.
        assertThat(steps.historyRead).containsExactly(1, 2).inOrder()
        assertThat(steps.sortRead).containsExactly(1, 2).inOrder()
    }

    @Test
    fun aLaterPassLetsThreadSortsThroughButHoldsFullSorts() {
        // B2-4: once the first sort is published, a stalled later pass does not freeze a block or "Not spam".
        assertThat(FirstRunState().allowsThreadSort).isFalse()
        steps.failHistory = 10
        runner().request(FirstRunTrigger.UPDATE)
        assertThat(store.read().allowsThreadSort).isFalse()
        steps.failHistory = 0
        runner().resume()

        steps.failHistory = 10
        val stalled = runner().request(FirstRunTrigger.CONTACTS_GRANTED).copy(waiting = true)

        assertThat(stalled.active).isTrue()
        assertThat(stalled.allowsFullSort).isFalse()
        assertThat(stalled.allowsThreadSort).isTrue()
    }

    @Test
    fun aPassThatMovesNothingShowsNoCard() {
        steps.moved = 0

        val state = runner().request(FirstRunTrigger.FRESH_INSTALL)

        assertThat(state.cardPending).isFalse()
        assertThat(state.movedToSpam).isEqualTo(0)
    }

    @Test
    fun aKillBetweenTheSortsCommitAndTheSaveCountsNothingTwice() {
        // B-12, B-25: the sort ran again after the kill; the card counts the table, not the sorts.
        steps.killAfterCommit = true
        val stopped = runner().request(FirstRunTrigger.UPDATE)
        assertThat(stopped.phase).isEqualTo(FirstRunPhase.SORT)

        val state = runner().resume()

        assertThat(steps.sorts).isEqualTo(2)
        assertThat(state.movedToSpam).isEqualTo(3)
        assertThat(state.allowedAutomatically).isEqualTo(2)
    }

    @Test
    fun cardCountsDoNotAddUpAcrossPassesUntilTheCardIsSeen() {
        runner().request(FirstRunTrigger.UPDATE)
        clock = 6_000L

        val state = runner().request(FirstRunTrigger.SWITCH_ON)

        // Still the update's card: it counts from the update's start, every row once.
        assertThat(state.cardSince).isEqualTo(5_000L)
        assertThat(state.movedToSpam).isEqualTo(6)
        assertThat(steps.table).hasSize(6)
    }

    @Test
    fun theListIsToldOnceWhenTheFirstSortIsPublished() {
        runner().request(FirstRunTrigger.UPDATE)
        runner().request(FirstRunTrigger.SWITCH_ON)

        assertThat(publishes).isEqualTo(1)
    }

    @Test
    fun aWaitingTriggerIsNotLostWhenTheAppIsKilledAsItsPassStarts() {
        // B-18: the trigger leaves the pending set only after the state of its pass is saved.
        steps.killAt = "history"
        runner().request(FirstRunTrigger.UPDATE)
        runner().request(FirstRunTrigger.CONTACTS_GRANTED)
        val dying = FirstRunRunner(store, steps, { clock }) {
            if (it.trigger == FirstRunTrigger.CONTACTS_GRANTED) error("killed")
        }

        runCatching { dying.resume() }

        assertThat(store.read().trigger).isEqualTo(FirstRunTrigger.CONTACTS_GRANTED)
        assertThat(store.pending).containsExactly(FirstRunTrigger.CONTACTS_GRANTED)
        assertThat(runner().resume().phase).isEqualTo(FirstRunPhase.DONE)
    }

    @Test
    fun theStateSurvivesAKillExactly() {
        steps.killAt = "sort"
        val stopped = runner().request(FirstRunTrigger.UPDATE)

        val reread = FirstRunStore(context.getSharedPreferences("first_run_test", Context.MODE_PRIVATE)).read()

        assertThat(reread).isEqualTo(stopped)
        assertThat(reread.startedAt).isEqualTo(5_000L)
        assertThat(seen.last()).isEqualTo(stopped)
    }
}
