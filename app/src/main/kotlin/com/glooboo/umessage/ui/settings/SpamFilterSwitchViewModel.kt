package com.glooboo.umessage.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.glooboo.umessage.spam.SpamRepository
import com.glooboo.umessage.ui.spam.ChatMoveCounter
import com.glooboo.umessage.ui.spam.ChatMoveCounting
import com.glooboo.umessage.ui.spam.SpamRepositoryProvider
import com.glooboo.umessage.ui.spam.SwitchConfirmer
import com.glooboo.umessage.ui.spam.applying
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Provider truth: the filter switch on the main Settings page. Its pending confirm lives here, so a rotation keeps the
 * dialog (and its count), as on the Spam protection page. The confirmed change is written through the repository; a
 * write that fails, or no repository, fires [failureFlow] so the page can say so (R2-4).
 */
class SpamFilterSwitchViewModel(
    application: Application,
    private val repository: () -> SpamRepository?,
    moveCounter: () -> ChatMoveCounter = { ChatMoveCounting.counter },
) : AndroidViewModel(application) {

    private val failures = Channel<Unit>(Channel.CONFLATED)

    /** Fires when the switch could not be written, as SettingsViewModel's does on the Spam protection page. */
    val failureFlow: Flow<Unit> = failures.receiveAsFlow()

    val switches = SwitchConfirmer(viewModelScope, moveCounter) { change ->
        runCatching { checkNotNull(repository()).updateSettings { it.applying(change) } }
            .onFailure { failures.trySend(Unit) }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as Application
                SpamFilterSwitchViewModel(app, { runCatching { SpamRepositoryProvider.get(app) }.getOrNull() })
            }
        }
    }
}
