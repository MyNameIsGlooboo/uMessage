package com.glooboo.umessage.ui.spam

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.glooboo.umessage.helpers.ProviderTruthFlag
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

/** A switch change waiting for its confirm, with the number of chats it moves ([chats] null: unknown). */
data class SwitchConfirm(val change: SortChange, val chats: Int?)

/**
 * The ask-first step of a switch that moves chats (answer 18, U11): [request] counts the chats that would move and
 * shows the confirm, [confirm] makes the change through [apply], [dismiss] leaves everything as it was. A count that
 * fails is "unknown", never a reason to skip the confirm.
 */
class SwitchConfirmer(
    private val scope: CoroutineScope,
    private val counter: () -> ChatMoveCounter = { ChatMoveCounting.counter },
    private val apply: suspend (SortChange) -> Unit,
) {
    private val pendingState = MutableStateFlow<SwitchConfirm?>(null)
    val pending: StateFlow<SwitchConfirm?> = pendingState.asStateFlow()

    fun request(change: SortChange) {
        scope.launch {
            val chats = runCatching { counter().chatsMoving(change) }.getOrNull()
            pendingState.value = SwitchConfirm(change, chats?.coerceAtLeast(0))
        }
    }

    fun confirm() {
        val change = pendingState.value?.change ?: return
        pendingState.value = null
        scope.launch { apply(change) }
    }

    fun dismiss() {
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
 * Auto-delete days wait for the warning (4.6): with [ProviderTruthFlag] on, a number of days deletes texts from the
 * phone itself, so [request] holds it until [confirm]; "keep forever", or the value already set, is written at once
 * through [write].
 */
class PurgeWarning(private val write: (Int) -> Unit) {
    private val pendingState = MutableStateFlow<Int?>(null)
    val pending: StateFlow<Int?> = pendingState.asStateFlow()

    fun request(days: Int, currentDays: Int) {
        if (ProviderTruthFlag.enabled && days != 0 && days != currentDays) pendingState.value = days else write(days)
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
