package com.glooboo.umessage.ui.conversations

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MarkReadRunnerTest {

    private val ids = (1L..TOTAL).toList()

    @Test
    fun throttleSkipsCountsInsideTheStepAndTheIntervalButAlwaysPublishesTheLast() {
        val throttle = ProgressThrottle(startAt = 0L)
        assertThat(throttle.shouldPublish(1, 120, 10L)).isFalse()
        assertThat(throttle.shouldPublish(49, 120, 20L)).isFalse()
        assertThat(throttle.shouldPublish(50, 120, 30L)).isTrue()
        assertThat(throttle.shouldPublish(51, 120, 40L)).isFalse()
        assertThat(throttle.shouldPublish(52, 120, 140L)).isTrue()
        assertThat(throttle.shouldPublish(120, 120, 141L)).isTrue()
    }

    @Test
    fun fractionIsZeroForNoTotalAndStaysBetweenZeroAndOne() {
        assertThat(MarkReadProgress(0, 0).fraction).isEqualTo(0f)
        assertThat(MarkReadProgress(1, 4).fraction).isEqualTo(0.25f)
        assertThat(MarkReadProgress(9, 4).fraction).isEqualTo(1f)
    }

    @Test
    fun progressGoesFromZeroToTotalInSteps() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val runner = MarkReadRunner(this, dispatcher, clock = { 0L })
        // A StateFlow collector conflates a loop that never suspends, so sample what the screen would see: the
        // published count at each chat, and the last one when the run ends (before the bar is cleared).
        val seen = mutableListOf<MarkReadProgress>()
        fun sample() {
            runner.progress.value?.let { if (seen.lastOrNull() != it) seen += it }
        }
        val marked = mutableListOf<Long>()
        var failed: Boolean? = null

        assertThat(runner.start(ids, { sample(); marked += it }) { sample(); failed = it }).isTrue()
        advanceUntilIdle()

        assertThat(marked).isEqualTo(ids)
        assertThat(failed).isFalse()
        assertThat(seen.map { it.done }).containsExactly(0, 50, 100, TOTAL.toInt()).inOrder()
        assertThat(seen.all { it.total == TOTAL.toInt() }).isTrue()
        assertThat(runner.progress.value).isNull()
    }

    @Test
    fun aSecondStartIsIgnoredWhileOneRuns() = runTest {
        val runner = MarkReadRunner(this, StandardTestDispatcher(testScheduler), clock = { 0L })
        val marked = mutableListOf<Long>()

        assertThat(runner.start(ids, { marked += it }) { }).isTrue()
        assertThat(runner.isRunning).isTrue()
        assertThat(runner.start(listOf(999L), { marked += it }) { }).isFalse()
        advanceUntilIdle()

        assertThat(marked).isEqualTo(ids)
        assertThat(runner.isRunning).isFalse()
        assertThat(runner.start(listOf(999L), { marked += it }) { }).isTrue()
        advanceUntilIdle()
    }

    @Test
    fun aFailureStopsTheRunReportsFailedAndClearsTheBar() = runTest {
        val runner = MarkReadRunner(this, UnconfinedTestDispatcher(testScheduler), clock = { 0L })
        var failed: Boolean? = null

        runner.start(ids, { check(it < 3L) }) { failed = it }
        advanceUntilIdle()

        assertThat(failed).isTrue()
        assertThat(runner.progress.value).isNull()
    }

    @Test
    fun aShortQuickRunShowsNoBarButASlowOneDoes() = runTest {
        var now = 0L
        val runner = MarkReadRunner(this, UnconfinedTestDispatcher(testScheduler), clock = { now })
        val seen = mutableListOf<MarkReadProgress>()
        fun sample() {
            runner.progress.value?.let { seen += it }
        }

        runner.start(listOf(1L, 2L, 3L), { sample() }) { sample() }
        advanceUntilIdle()
        assertThat(seen).isEmpty()

        runner.start(listOf(1L, 2L, 3L), { now += MARK_READ_BAR_DELAY_MS; sample() }) { sample() }
        advanceUntilIdle()
        assertThat(seen.map { it.done }).containsExactly(1, 2, 3).inOrder()
        assertThat(runner.isRunning).isFalse()
    }

    @Test
    fun aFailedRunKeepsUndoForTheChatsAlreadyMarked() {
        val events = markAllReadEvents(token = 7L, done = 2, failed = true)
        assertThat(events).containsExactly(
            ConversationsEvent.Failed, ConversationsEvent.MarkedAllRead(7L, 2),
        ).inOrder()
        assertThat(markAllReadEvents(token = 7L, done = 0, failed = true))
            .containsExactly(ConversationsEvent.Failed)
        assertThat(markAllReadEvents(token = 7L, done = 3, failed = false))
            .containsExactly(ConversationsEvent.MarkedAllRead(7L, 3))
    }

    @Test
    fun nothingToMarkStartsNothing() {
        val scope = TestScope()
        val runner = MarkReadRunner(scope, StandardTestDispatcher(scope.testScheduler))
        assertThat(runner.start(emptyList(), { }) { }).isFalse()
        assertThat(runner.isRunning).isFalse()
    }

    private companion object {
        const val TOTAL = 120L
    }
}
