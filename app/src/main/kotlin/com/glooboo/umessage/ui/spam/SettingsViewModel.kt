package com.glooboo.umessage.ui.spam

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.glooboo.umessage.spam.SpamRepository
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.UnknownInboxNotify
import com.glooboo.umessage.spam.UnknownSenderMode

data class SettingsUiState(
    val loading: Boolean = true,
    val settings: SpamSettings = SpamSettings(),
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
) : AndroidViewModel(application) {

    val state: StateFlow<SettingsUiState> = repository.settings
        .map { SettingsUiState(loading = false, settings = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun setMode(mode: UnknownSenderMode) = update { it.copy(mode = mode) }
    fun setPurgeDays(days: Int) = update { it.copy(purgeAfterDays = days) }
    fun setNotifyOnBlock(enabled: Boolean) = update { it.copy(notifyOnBlock = enabled) }
    fun setUnknownInboxNotify(choice: UnknownInboxNotify) = update { it.copy(unknownInboxNotify = choice) }
    fun setOpenLinkAction(enabled: Boolean) = update { it.copy(openLinkAction = enabled) }
    fun setBlockOutsideRegion(region: String) = update { it.copy(blockOutsideRegion = region) }

    private fun update(transform: (SpamSettings) -> SpamSettings) {
        viewModelScope.launch { runCatching { repository.updateSettings(transform) } }
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
