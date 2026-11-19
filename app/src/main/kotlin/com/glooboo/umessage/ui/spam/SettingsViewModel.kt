package com.glooboo.umessage.ui.spam

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.SpamRepository
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.UnknownInboxNotify
import com.glooboo.umessage.spam.UnknownSenderMode

data class SettingsUiState(
    val loading: Boolean = true,
    val settings: SpamSettings = SpamSettings(),
    /** Provider truth (U11): the filter switch was tapped; the change waits for this confirm. */
    val switchConfirm: SwitchConfirm? = null,
    /** Provider truth: days chosen for auto-delete, waiting for the warning to be accepted. */
    val purgeWarning: Int? = null,
) {
    /** Preset retention choices plus the stored value if it is custom. 0 (keep forever) is last. */
    val purgeOptions: List<Int>
        get() = (PRESET_PURGE_DAYS + settings.purgeAfterDays).distinct().sortedBy { if (it == 0) Int.MAX_VALUE else it }

    companion object {
        val PRESET_PURGE_DAYS = listOf(7, 14, 30, 90, 0)
    }
}

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
fun SpamSettings.with(change: SortChange): SpamSettings = when (change) {
    is SortChange.Filter -> copy(filterOn = change.on)
    is SortChange.Group -> when (change.group) {
        AllowGroup.CODE -> copy(codeGroupOn = change.on)
        AllowGroup.WROTE -> copy(wroteGroupOn = change.on)
    }
}

class SettingsViewModel(
    application: Application,
    private val repository: SpamRepository,
    moveCounter: () -> ChatMoveCounter = { ChatMoveCounting.counter },
) : AndroidViewModel(application) {

    private val failures = Channel<Unit>(Channel.CONFLATED)

    /** Fires when a settings write failed, so the screen can say so. */
    val failureFlow: Flow<Unit> = failures.receiveAsFlow()

    private val switches = SwitchConfirmer(viewModelScope, moveCounter) { change -> write { it.with(change) } }
    private val purgeWarning = MutableStateFlow<Int?>(null)

    val state: StateFlow<SettingsUiState> = combine(repository.settings, switches.pending, purgeWarning) { s, c, p ->
        SettingsUiState(loading = false, settings = s, switchConfirm = c, purgeWarning = p)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setMode(mode: UnknownSenderMode) = update { it.copy(mode = mode) }

    /** Provider truth: the filter switch asks first and names the number of chats that move (U11). */
    fun requestFilter(on: Boolean) = switches.request(SortChange.Filter(on))
    fun confirmSwitch() = switches.confirm()
    fun dismissSwitch() = switches.dismiss()

    /**
     * Auto-delete. With [ProviderTruthFlag] on, a number of days deletes texts from the phone itself, so that choice
     * is warned about first; "keep forever", or the value already set, is written at once.
     */
    fun setPurgeDays(days: Int) {
        if (ProviderTruthFlag.enabled && days != 0 && days != state.value.settings.purgeAfterDays) {
            purgeWarning.value = days
        } else {
            update { it.copy(purgeAfterDays = days) }
        }
    }

    /** The warning was accepted: the chosen days are written. */
    fun confirmPurgeDays() {
        val days = purgeWarning.value ?: return
        purgeWarning.value = null
        update { it.copy(purgeAfterDays = days) }
    }

    fun dismissPurgeDays() {
        purgeWarning.value = null
    }

    fun setNotifyOnBlock(enabled: Boolean) = update { it.copy(notifyOnBlock = enabled) }
    fun setUnknownInboxNotify(choice: UnknownInboxNotify) = update { it.copy(unknownInboxNotify = choice) }
    fun setOpenLinkAction(enabled: Boolean) = update { it.copy(openLinkAction = enabled) }
    fun setBlockOutsideRegion(region: String) = update { it.copy(blockOutsideRegion = region) }

    private fun update(transform: (SpamSettings) -> SpamSettings) {
        viewModelScope.launch { write(transform) }
    }

    private suspend fun write(transform: (SpamSettings) -> SpamSettings) {
        runCatching { repository.updateSettings(transform) }.onFailure { failures.trySend(Unit) }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as Application
                SettingsViewModel(app, SpamRepositoryProvider.get(app))
            }
        }
    }
}
