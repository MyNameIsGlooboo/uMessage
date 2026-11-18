package com.glooboo.umessage.ui.spam

import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test

/** The process-wide state of what "Not spam" is moving. */
class NotSpamMovesTest {

    private var now = START
    private val moves = NotSpamMoves { now }

    private fun batch(vararg ids: Long) =
        ids.map { BlockedMessage(it, "+15550001234", "text $it", 1, false, it * 1000, DecisionReason.UNKNOWN_SENDER) }

    @Test
    fun beginSetsTheTotalAndTheStartTime() {
        assertThat(moves.state.value).isEqualTo(MoveState())
        assertThat(moves.state.value.running).isFalse()
        moves.begin(batch(1, 2, 3))
        val state = moves.state.value
        assertThat(state.running).isTrue()
        assertThat(state.total).isEqualTo(3)
        assertThat(state.done).isEqualTo(0)
        assertThat(state.startedAt).isEqualTo(START)
        assertThat(state.inFlight.keys).containsExactly(1L, 2L, 3L)
    }

    @Test
    fun stepCountsAndNeverPassesTheTotal() {
        moves.begin(batch(1, 2))
        repeat(5) { moves.step() }
        assertThat(moves.state.value.done).isEqualTo(2)
        assertThat(moves.state.value.fraction).isEqualTo(1f)
    }

    @Test
    fun theEndOfTheOnlyBatchGoesBackToIdle() {
        moves.begin(batch(1, 2))
        moves.step()
        moves.end(listOf(1L, 2L))
        assertThat(moves.state.value).isEqualTo(MoveState())
    }

    @Test
    fun overlappingBatchesAddUpAndTheFirstEndKeepsTheSecondBatch() {
        moves.begin(batch(1, 2))
        moves.step()
        now += LATER_MS
        moves.begin(batch(3, 4, 5))
        val both = moves.state.value
        assertThat(both.total).isEqualTo(5)
        assertThat(both.done).isEqualTo(1)
        assertThat(both.startedAt).isEqualTo(START)

        moves.end(listOf(1L, 2L))
        val rest = moves.state.value
        assertThat(rest.inFlight.keys).containsExactly(3L, 4L, 5L)
        assertThat(rest.total).isEqualTo(5)
        assertThat(rest.running).isTrue()

        moves.end(listOf(3L, 4L, 5L))
        assertThat(moves.state.value).isEqualTo(MoveState())
    }

    @Test
    fun aNewBatchAfterIdleStartsAtItsOwnTime() {
        moves.begin(batch(1))
        moves.end(listOf(1L))
        now += LATER_MS
        moves.begin(batch(2))
        assertThat(moves.state.value.startedAt).isEqualTo(START + LATER_MS)
    }

    @Test
    fun anOrphanAnnouncementReachesACollector() {
        runBlocking {
            val heard = async(start = CoroutineStart.UNDISPATCHED) { moves.orphanDone.first() }
            moves.announceOrphan(ORPHAN_COUNT)
            assertThat(heard.await()).isEqualTo(ORPHAN_COUNT)
        }
    }

    private companion object {
        const val START = 10_000L
        const val LATER_MS = 500L
        const val ORPHAN_COUNT = 7
    }
}
