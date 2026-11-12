package com.glooboo.umessage.ui.forwarded

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glooboo.umessage.extensions.getThreadId
import com.glooboo.umessage.helpers.NavPrefs
import com.glooboo.umessage.helpers.W3aPrefs
import com.glooboo.umessage.helpers.refreshConversations
import com.glooboo.umessage.helpers.refreshMessages
import com.glooboo.umessage.spam.ForwardLogEntry
import com.glooboo.umessage.spam.ForwardRule
import com.glooboo.umessage.spam.data.DefaultForwardLogRepository
import com.glooboo.umessage.spam.data.ForwardResend
import com.glooboo.umessage.spam.data.ForwardResend.RetryResult
import com.glooboo.umessage.spam.data.SpamDatabase
import com.glooboo.umessage.spam.engine.SpamEngine
import com.glooboo.umessage.ui.spam.AndroidForwardLockGate
import com.glooboo.umessage.ui.spam.ForwardGatePolicy
import com.glooboo.umessage.ui.spam.SimDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.commons.helpers.SimpleContactsHelper

/**
 * The Forwarded tab: the forward rules, the forward log, the pause switch and retries. Retrying reads the original
 * message again (nothing is stored) and sends one paid SMS per failed forward.
 */
class ForwardedViewModel(application: Application) : AndroidViewModel(application) {

    private data class Ui(
        val sims: List<com.glooboo.umessage.ui.spam.SimInfo> = emptyList(),
        val names: Map<String, String> = emptyMap(),
        val busy: Boolean = false,
    )

    private val app: Application get() = getApplication()
    private val prefs = NavPrefs.get(application)
    private val ui = MutableStateFlow(Ui())
    private val _events = MutableSharedFlow<ForwardedEvent>(extraBufferCapacity = 4)

    val events: SharedFlow<ForwardedEvent> = _events.asSharedFlow()

    private val rulesFlow: Flow<List<ForwardRule>> =
        flow { emitAll(SpamEngine.forwardRepository(app).rules) }.catch { emit(emptyList()) }

    private val entriesFlow: Flow<List<ForwardLogEntry>> =
        flow { emitAll(DefaultForwardLogRepository(SpamDatabase.getInstance(app).forwardLogDao()).entries) }
            // Opening the database the first time reads files: not on the main thread (L5).
            .flowOn(Dispatchers.IO)
            .catch { emit(emptyList()) }

    private val dailyLimitFlow: Flow<Int> = W3aPrefs.get(application).dailyForwardLimitFlow.catch { emit(0) }

    val state: StateFlow<ForwardedUiState> = combine(
        rulesFlow, entriesFlow, prefs.forwardPausedFlow, dailyLimitFlow, ui,
    ) { rules, entries, paused, dailyLimit, ui ->
        ForwardedUiState(
            loading = false,
            rules = rules,
            entries = entries,
            sims = ui.sims,
            paused = paused,
            names = ui.names,
            busy = ui.busy,
            dailyLimit = dailyLimit,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ForwardedUiState())

    init {
        viewModelScope.launch {
            val sims = withContext(Dispatchers.IO) { runCatching { SimDirectory.load(app) }.getOrDefault(emptyList()) }
            ui.update { it.copy(sims = sims) }
        }
        viewModelScope.launch { entriesFlow.collect { resolveNames(it) } }
    }

    private suspend fun resolveNames(entries: List<ForwardLogEntry>) {
        val wanted = entries.flatMap { listOf(it.sourceAddress, it.target) }.toSet() - ui.value.names.keys
        if (wanted.isEmpty()) return
        val found = withContext(Dispatchers.IO) {
            val helper = SimpleContactsHelper(app)
            wanted.associateWith { address ->
                runCatching { helper.getNameFromPhoneNumber(address) }.getOrNull()?.takeIf { it.isNotBlank() }.orEmpty()
            }
        }
        ui.update { it.copy(names = it.names + found) }
    }

    fun setPaused(paused: Boolean) {
        prefs.forwardPaused = paused
    }

    /** Whether changing the pause switch to [paused] must pass the app lock first (resuming does, pausing not). */
    fun pauseNeedsUnlock(paused: Boolean): Boolean =
        ForwardGatePolicy.pauseChangeNeedsCheck(paused, AndroidForwardLockGate(app).lockEnabled())

    /** The failed forwards up to now count as seen (clears the dot on the tab). */
    fun markSeen() {
        prefs.forwardedSeenAt = System.currentTimeMillis()
    }

    fun retry(ids: List<Long>) {
        if (ids.isEmpty() || ui.value.busy) return
        ui.update { it.copy(busy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val results = try {
                ids.map { id ->
                    runCatching { ForwardResend.retry(app, id) }.getOrDefault(ForwardResend.RetryResult.FAILED)
                }
            } finally {
                ui.update { it.copy(busy = false) }
            }
            runCatching { refreshMessages() }
            runCatching { refreshConversations() }
            _events.emit(retriedEvent(results))
        }
    }

    /** "Retry all": the failed forwards of the last day only (M2); older ones are retried one at a time. */
    fun retryAll() = retry(state.value.retryAllIds(System.currentTimeMillis()))

    /** Finds the chat with a forward's original sender; [onResult] runs on the main thread. */
    fun openThread(entry: ForwardLogEntry, onResult: (threadId: Long, title: String) -> Unit) {
        viewModelScope.launch {
            val threadId = withContext(Dispatchers.IO) { app.getThreadId(entry.sourceAddress) }
            if (threadId != 0L) onResult(threadId, state.value.nameOf(entry.sourceAddress))
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/** What a batch of retries did, for the snackbar. */
internal fun retriedEvent(results: List<RetryResult>): ForwardedEvent.Retried {
    fun count(vararg of: RetryResult) = results.count { it in of }
    return ForwardedEvent.Retried(
        sent = count(RetryResult.SENT),
        failed = count(RetryResult.FAILED),
        missing = count(RetryResult.ORIGINAL_MISSING),
        paused = count(RetryResult.PAUSED),
        ruleOff = count(RetryResult.RULE_OFF),
        limited = count(RetryResult.LIMITED),
    )
}
