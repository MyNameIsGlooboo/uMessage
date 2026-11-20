package com.glooboo.umessage.spam

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.FutureTask

/**
 * Step B, the sorting queue (B-10, B-17, B2-4): requests merge into one deadline, the first run's gate holds sorts and
 * lets them out as one full sort, a failed sort is tried again a few times, and once the first sort is published a
 * sort of single chats is not held by a later pass. Runs on a fake scheduler and clock.
 */
@RunWith(AndroidJUnit4::class)
class SortQueueTest {

    private var now = 1_000L

    /** Every task the queue scheduled, with the time it is due. */
    private val tasks = mutableListOf<Pair<Long, FutureTask<Unit>>>()

    private var state = PUBLISHED
    private var failing = false
    private val sorts = mutableListOf<Set<Long>?>()
    private val published = mutableListOf<SortResult>()

    private val queue = SortQueue(
        scheduler = { task, delayMs -> FutureTask(task, Unit).also { tasks += (now + delayMs) to it } },
        gate = { state },
        sort = { threads ->
            sorts += threads
            if (failing) SortResult.Failed("contacts") else SortResult.Applied(1, 0, 0)
        },
        published = { published += it },
        clock = { now },
    )

    /** Moves the clock to [to], running every task due by then in the order of their deadlines. */
    private fun runUntil(to: Long) {
        while (true) {
            val next = waiting().filter { it.first <= to }.minByOrNull { it.first } ?: break
            now = maxOf(now, next.first)
            next.second.run()
        }
        now = to
    }

    private fun waiting() = tasks.filter { !it.second.isDone }

    @Test
    fun aThreadSortRunsAtOnce() {
        queue.requestThreads(listOf(7L))

        assertThat(waiting().map { it.first }).containsExactly(1_000L)
        runUntil(1_000L)
        assertThat(sorts).containsExactly(setOf(7L))
        assertThat(published).hasSize(1)
    }

    @Test
    fun fullSortsMergeIntoOneDeadlineAtTheLatestDebounce() {
        queue.requestFull(300L)
        queue.requestFull(3_000L)
        queue.requestFull(300L)

        assertThat(waiting().map { it.first }).containsExactly(4_000L)
        runUntil(3_999L)
        assertThat(sorts).isEmpty()
        runUntil(10_000L)
        assertThat(sorts).containsExactly(null)
    }

    @Test
    fun aThreadSortPullsAWaitingFullSortForwardAndTheFullSortCoversIt() {
        queue.requestFull(3_000L)
        queue.requestThreads(listOf(7L))
        // A full sort asked after the thread sort does not push the deadline back.
        queue.requestFull(3_000L)

        assertThat(waiting().map { it.first }).containsExactly(1_000L)
        runUntil(10_000L)
        assertThat(sorts).containsExactly(null)
    }

    @Test
    fun beforeTheFirstPublishEverySortWaitsAndRunsOnceAsOneFullSort() {
        state = FirstRunState(phase = FirstRunPhase.SORT, trigger = FirstRunTrigger.UPDATE)
        queue.requestThreads(listOf(7L))
        queue.requestFull()
        runUntil(2_000L)
        queue.requestThreads(listOf(8L))
        runUntil(3_000L)
        assertThat(sorts).isEmpty()

        state = PUBLISHED
        queue.releaseGate()
        runUntil(4_000L)

        assertThat(sorts).containsExactly(null)
        // Nothing waits any more: a second release sorts nothing.
        queue.releaseGate()
        assertThat(waiting()).isEmpty()
    }

    @Test
    fun releasingTheGateWithNothingMissedSortsNothing() {
        queue.releaseGate()

        assertThat(waiting()).isEmpty()
        assertThat(sorts).isEmpty()
    }

    @Test
    fun aStalledLaterPassLetsThreadSortsThroughAndHoldsFullSorts() {
        // B2-4: a block or "Not spam" must move its chat while a later pass cannot finish.
        state = FirstRunState(
            phase = FirstRunPhase.HISTORY, trigger = FirstRunTrigger.CONTACTS_GRANTED, sortPublished = true,
            waiting = true,
        )
        queue.requestFull()
        queue.requestThreads(listOf(7L))
        runUntil(2_000L)
        assertThat(sorts).containsExactly(setOf(7L))

        state = PUBLISHED
        queue.releaseGate()
        runUntil(3_000L)

        assertThat(sorts).containsExactly(setOf(7L), null).inOrder()
    }

    @Test
    fun aFailedSortIsTriedAgainAtMostThreeTimes() {
        failing = true
        queue.requestFull()

        runUntil(1_000L + 10 * SortQueue.RETRY_MS)

        assertThat(sorts).hasSize(1 + SortQueue.MAX_RETRIES)
        assertThat(waiting()).isEmpty()
    }

    @Test
    fun aSortThatWorksResetsTheRetries() {
        failing = true
        queue.requestFull()
        runUntil(1_000L)
        failing = false
        runUntil(1_000L + SortQueue.RETRY_MS)
        assertThat(sorts).hasSize(2)

        failing = true
        queue.requestFull()
        runUntil(now + 10 * SortQueue.RETRY_MS)

        assertThat(sorts).hasSize(2 + 1 + SortQueue.MAX_RETRIES)
    }

    private companion object {
        val PUBLISHED = FirstRunState(phase = FirstRunPhase.DONE, sortPublished = true)
    }
}
