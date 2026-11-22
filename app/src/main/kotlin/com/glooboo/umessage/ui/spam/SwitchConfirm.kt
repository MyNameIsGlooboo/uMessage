package com.glooboo.umessage.ui.spam

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.SpamSettings

/** A switch change that moves chats between Messages and Spam (provider truth: U11, D3 A). */
sealed interface SortChange {
    /** The one spam filter switch goes [on] or off. */
    data class Filter(val on: Boolean) : SortChange

    /** The group rule [group] goes [on] or off. */
    data class Group(val group: AllowGroup, val on: Boolean) : SortChange
}

/**
 * How many chats a [SortChange] would move. Null when that cannot be known (yet); the confirm then names no number.
 * The chat sorter of step B provides the real count through [ChatMoveCounting.counter]. Called from the main thread:
 * an implementation that reads the store moves to a background dispatcher itself.
 */
fun interface ChatMoveCounter {
    suspend fun chatsMoving(change: SortChange): Int?
}

/** Where the screens find the [ChatMoveCounter]. Until the sorter sets one, every count is unknown. */
object ChatMoveCounting {
    val UNKNOWN = ChatMoveCounter { null }

    @Volatile
    var counter: ChatMoveCounter = UNKNOWN
}

/**
 * A switch change waiting for its confirm. [chats] is the number of chats it moves; null while [counting] or when it
 * cannot be known. The dialog shows at once and fills in the number when it arrives (G-3).
 */
data class SwitchConfirm(val change: SortChange, val chats: Int?, val counting: Boolean = false)

/**
 * The ask-first step of a switch that moves chats (answer 18, U11): [request] shows the confirm at once and counts the
 * chats that would move in one job (a new request cancels the old one), [confirm] makes the change through [apply],
 * [dismiss] leaves everything as it was. A count that comes after the confirm or the dismiss is dropped; a count that
 * fails is "unknown", never a reason to skip the confirm.
 */
class SwitchConfirmer(
    private val scope: CoroutineScope,
    private val counter: () -> ChatMoveCounter = { ChatMoveCounting.counter },
    private val apply: suspend (SortChange) -> Unit,
) {
    private val pendingState = MutableStateFlow<SwitchConfirm?>(null)
    val pending: StateFlow<SwitchConfirm?> = pendingState.asStateFlow()
    private var countJob: Job? = null

    fun request(change: SortChange) {
        countJob?.cancel()
        val asked = SwitchConfirm(change, chats = null, counting = true)
        pendingState.value = asked
        countJob = scope.launch {
            // A failed count is "unknown"; the confirm still shows. A cancelled count writes nothing.
            val chats = runCatching { counter().chatsMoving(change) }.also { ensureActive() }.getOrNull()
            pendingState.compareAndSet(asked, asked.copy(chats = chats?.coerceAtLeast(0), counting = false))
        }
    }

    fun confirm() {
        val change = pendingState.value?.change ?: return
        stop()
        scope.launch { apply(change) }
    }

    fun dismiss() = stop()

    private fun stop() {
        countJob?.cancel()
        countJob = null
        pendingState.value = null
    }
}

/** The settings after a confirmed [change]. */
fun SpamSettings.applying(change: SortChange): SpamSettings = when (change) {
    is SortChange.Filter -> copy(filterOn = change.on)
    is SortChange.Group -> when (change.group) {
        AllowGroup.CODE -> copy(codeGroupOn = change.on)
        AllowGroup.WROTE -> copy(wroteGroupOn = change.on)
    }
}

/**
 * Auto-delete days wait for the warning (4.6): a number of days deletes texts from the phone itself, so [request]
 * holds it until [confirm]; "keep forever", or the value already set, is written at once through [write].
 */
class PurgeWarning(private val write: (Int) -> Unit) {
    private val pendingState = MutableStateFlow<Int?>(null)
    val pending: StateFlow<Int?> = pendingState.asStateFlow()

    fun request(days: Int, currentDays: Int) {
        if (days != 0 && days != currentDays) pendingState.value = days else write(days)
    }

    /** The warning was accepted: the chosen days are written. */
    fun confirm() {
        val days = pendingState.value ?: return
        pendingState.value = null
        write(days)
    }

    fun dismiss() {
        pendingState.value = null
    }
}
