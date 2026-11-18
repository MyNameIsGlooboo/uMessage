package com.glooboo.umessage.ui.spam

import com.glooboo.umessage.spam.BlockedMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The messages a "Not spam" is moving right now, over all screens. */
data class MoveState(
    /** By blocked id; kept until the batch ends. */
    val inFlight: Map<Long, BlockedMessage> = emptyMap(),
    val done: Int = 0,
    val total: Int = 0,
    /** Clock time of the first [NotSpamMoves.begin] since the moves were idle. */
    val startedAt: Long = 0L,
) {
    val running: Boolean get() = total > 0

    /** 0f..1f, for the bar. */
    val fraction: Float get() = if (total <= 0) 0f else (done.toFloat() / total).coerceIn(0f, 1f)
}

/**
 * One process-wide source of truth for what "Not spam" is moving, so the Spam list and the sender page (which have
 * separate view models) agree, a reopened sender page shows the running move, and a second tap cannot start a second
 * loop over the same messages. Two batches that overlap add up: "16 of 40" becomes "16 of 160".
 */
class NotSpamMoves(private val clock: () -> Long = System::currentTimeMillis) {
    private val moves = MutableStateFlow(MoveState())
    val state: StateFlow<MoveState> = moves.asStateFlow()

    private val orphans = MutableSharedFlow<Int>(replay = 0, extraBufferCapacity = ORPHAN_BUFFER)

    /** A move whose screen is gone has finished; the value is how many messages reached the inbox. */
    val orphanDone: SharedFlow<Int> = orphans.asSharedFlow()

    /** A batch of [messages] starts moving. */
    fun begin(messages: List<BlockedMessage>) {
        if (messages.isEmpty()) return
        val now = clock()
        moves.update { s ->
            s.copy(
                inFlight = s.inFlight + messages.associateBy { it.id },
                total = s.total + messages.size,
                startedAt = if (s.running) s.startedAt else now,
            )
        }
    }

    /** One more message is handled; never above the total. */
    fun step() = moves.update { if (it.running) it.copy(done = (it.done + 1).coerceAtMost(it.total)) else it }

    /** The batch with [ids] is over. When nothing else is in flight the state is idle again. */
    fun end(ids: Collection<Long>) = moves.update { s ->
        val left = s.inFlight - ids.toSet()
        if (left.isEmpty()) MoveState() else s.copy(inFlight = left)
    }

    /** A move whose screen is gone has finished; [toInbox] messages reached the inbox. */
    fun announceOrphan(toInbox: Int) {
        orphans.tryEmit(toInbox)
    }

    companion object {
        private const val ORPHAN_BUFFER = 4

        val shared = NotSpamMoves()
    }
}
