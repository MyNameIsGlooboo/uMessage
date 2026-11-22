package com.glooboo.umessage.ui.spam

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.glooboo.umessage.spam.SpamRepository
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.UnknownInboxNotify

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

class SettingsViewModel(
    application: Application,
    private val repository: SpamRepository,
    moveCounter: () -> ChatMoveCounter = { ChatMoveCounting.counter },
) : AndroidViewModel(application) {

    private val failures = Channel<Unit>(Channel.CONFLATED)

    /** Fires when a settings write failed, so the screen can say so. */
    val failureFlow: Flow<Unit> = failures.receiveAsFlow()

    /** Provider truth: the filter switch asks first and names the number of chats that move (U11). */
    val switches = SwitchConfirmer(viewModelScope, moveCounter) { change -> write { it.applying(change) } }

    /** Provider truth: days for auto-delete wait for the warning to be accepted. */
    val purge = PurgeWarning { days -> update { it.copy(purgeAfterDays = days) } }

    val state: StateFlow<SettingsUiState> = combine(repository.settings, switches.pending, purge.pending) { s, c, p ->
        SettingsUiState(loading = false, settings = s, switchConfirm = c, purgeWarning = p)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    /**
     * Auto-delete. A number of days deletes texts from the phone itself, so that choice is warned about first; "keep
     * forever", or the value already set, is written at once.
     */
    fun setPurgeDays(days: Int) = purge.request(days, state.value.settings.purgeAfterDays)

    fun setNotifyOnBlock(enabled: Boolean) = update { it.copy(notifyOnBlock = enabled) }
    fun setUnknownInboxNotify(choice: UnknownInboxNotify) = update { it.copy(unknownInboxNotify = choice) }
    fun setOpenLinkAction(enabled: Boolean) = update { it.copy(openLinkAction = enabled) }

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
