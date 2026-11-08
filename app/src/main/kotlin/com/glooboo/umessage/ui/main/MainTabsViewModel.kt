package com.glooboo.umessage.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glooboo.umessage.helpers.HiddenChats
import com.glooboo.umessage.helpers.NavPrefs
import com.glooboo.umessage.spam.ForwardRule
import com.glooboo.umessage.spam.ForwardingAvailability
import com.glooboo.umessage.spam.data.DefaultForwardLogRepository
import com.glooboo.umessage.spam.data.SpamDatabase
import com.glooboo.umessage.spam.engine.SpamEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the bottom bar needs to know about forwarding. */
data class MainTabsState(
    /** False until the forward rules were read once. */
    val loaded: Boolean = false,
    val forwardRuleCount: Int = 0,
    val showForwarded: Boolean? = null,
    val forwardPaused: Boolean = false,
    /** Forwards that failed since the Forwarded tab was last looked at (a dot, never a number). */
    val failedUnseen: Int = 0,
    /** Failed forwards in the log, seen or not: they keep the Forwarded tab after its last rule is deleted. */
    val failedForwards: Int = 0,
)

class MainTabsViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs = NavPrefs.get(application)

    /** False in a build without forwarding: no Forwarded tab, no forward log reads. */
    private val forwardingAvailable = ForwardingAvailability.isAvailable(application)

    private val rules: Flow<List<ForwardRule>?> =
        flow { emitAll(SpamEngine.forwardRepository(getApplication()).rules) }
            .map<List<ForwardRule>, List<ForwardRule>?> { it }
            .catch { emit(emptyList()) }
            // The first database open moves old backups aside: file work, kept off the main thread (L5).
            .flowOn(Dispatchers.IO)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val failedUnseen: Flow<Int> = prefs.forwardedSeenAtFlow.flatMapLatest { seenAt ->
        if (!forwardingAvailable) return@flatMapLatest flowOf(0)
        flow {
            val dao = SpamDatabase.getInstance(getApplication()).forwardLogDao()
            emitAll(DefaultForwardLogRepository(dao).failedSince(seenAt))
        }
            .catch { emit(0) }
            .flowOn(Dispatchers.IO)
    }

    private val failedForwards: Flow<Int> = if (!forwardingAvailable) flowOf(0) else flow {
        val dao = SpamDatabase.getInstance(getApplication()).forwardLogDao()
        emitAll(DefaultForwardLogRepository(dao).failedSince(0L))
    }
        .catch { emit(0) }
        .flowOn(Dispatchers.IO)

    private val codes = MutableStateFlow<List<FoundCode>>(emptyList())

    /** B9, ID-23: codes received in the last 24 hours, read or unread, for the empty search page. */
    val recentCodes: StateFlow<List<FoundCode>> = codes.asStateFlow()

    /** Reads the codes again; called when the search opens, so the list is current without watching the inbox. */
    fun refreshRecentCodes() {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            // B06: a code from a hidden chat is not offered until the chat is unlocked.
            val received = HiddenChats.get(getApplication()).withoutHidden(RecentCodes.read(getApplication(), now)) {
                it.threadId
            }
            codes.value = RecentCodes.pick(received, now)
        }
    }

    val state: StateFlow<MainTabsState> = combine(
        rules, prefs.showForwardedTabFlow, prefs.forwardPausedFlow, failedUnseen, failedForwards,
    ) { rules, show, paused, failed, failedListed ->
        MainTabsState(
            loaded = rules != null,
            forwardRuleCount = rules?.size ?: 0,
            showForwarded = show.takeIf { forwardingAvailable },
            forwardPaused = paused && forwardingAvailable,
            failedUnseen = failed,
            failedForwards = failedListed,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MainTabsState())
}
