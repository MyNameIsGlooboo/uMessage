package com.glooboo.umessage.ui.spam

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.glooboo.umessage.helpers.refreshConversations
import kotlinx.coroutines.withContext
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.RestoreOutcome
import com.glooboo.umessage.spam.RestoreReceipt
import com.glooboo.umessage.spam.SpamRepository
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.SpamStats
import com.glooboo.umessage.spam.UnknownSenderMode

/** All blocked messages of one sender, newest first. The sender address is the group key. */
data class SenderGroup(val address: String, val messages: List<BlockedMessage>) {
    val latest: BlockedMessage get() = messages.first()
}

/**
 * The "Always allow?" question asked after "Not spam" (T12 / SU-16). [spoofable] lists the senders whose names
 * anyone can send under, so the prompt can warn about them. [messageCount] is how many messages went back, so the
 * prompt says "message" or "messages" to match.
 */
data class AllowPrompt(
    val token: Long,
    val addresses: List<String>,
    val spoofable: List<String>,
    val messageCount: Int = addresses.size,
    /** I22: an allow rule for messages like the restored one, offered when one sender was restored. */
    val similar: SimilarRuleSuggestion.Suggestion? = null,
    val includeSimilar: Boolean = false,
)

data class BlockedUiState(
    val loading: Boolean = true,
    val groups: List<SenderGroup> = emptyList(),
    val stats: SpamStats = SpamStats(0, 0, 0),
    val selected: Set<String> = emptySet(),
    val sims: List<SimInfo> = emptyList(),
    /** Current unknown-sender mode, shown on the stats card with a shortcut to the spam settings. */
    val mode: UnknownSenderMode = UnknownSenderMode.SMART,
    /** Rules by id, so each blocked message can say which rule blocked it (SU-22). */
    val rules: Map<Long, SpamRule> = emptyMap(),
    /** Blocked messages older than this many days are deleted; 0 keeps them (SU-23). */
    val purgeAfterDays: Int = 30,
    val allowPrompt: AllowPrompt? = null,
    /** The chip chosen under the banner (ID-14); [groups] only holds what it lets through. */
    val filter: BlockedFilter = BlockedFilter.All,
    /** How many senders each chip would list, over all held messages (rows, not messages). */
    val filterCounts: Map<BlockedFilter, Int> = emptyMap(),
    /** All held messages, whatever the filter: zero means the tab is truly empty. */
    val heldCount: Int = groups.sumOf { it.messages.size },
    /** Messages moved to the inbox lately, newest first, behind the "N restored" link (BR-10). */
    val restored: List<RestoredEntry> = emptyList(),
    /**
     * A restore or a delete is still inside its undo window. The sender thread stays open while it is, so its Undo
     * snackbar is not lost with the screen.
     */
    val hasPendingUndo: Boolean = false,
) {
    val selecting: Boolean get() = selected.isNotEmpty()
}

sealed interface BlockedEvent {
    /**
     * Shown with an Undo action. The messages are in the inbox already (and the allow rules stored, when [allowed] >
     * 0); Undo takes both back. [count] messages went to the inbox; [mediaLost] of the picture messages among all
     * restored ones lost their pictures (never downloaded, download failed or expired), which the snackbar says
     * plainly (SU-17). Picture messages with neither pictures nor text are not in [count]: nothing of them reached
     * the inbox.
     */
    data class Restored(val token: Long, val count: Int, val allowed: Int = 0, val mediaLost: Int = 0) : BlockedEvent

    /** [count] kept picture messages could not be put in the inbox. They are still in Blocked. */
    data class RestoreFailed(val count: Int) : BlockedEvent

    /** Shown with an Undo action; the deletion becomes final unless the user chooses Undo. */
    data class Deleted(val token: Long, val count: Int) : BlockedEvent
    data object Failed : BlockedEvent

    /** The unknown-sender mode was changed from this screen; Undo puts [previous] back (P1-*). */
    data class ModeChanged(val previous: UnknownSenderMode, val mode: UnknownSenderMode) : BlockedEvent

    /**
     * The allow rules chosen after Not spam are kept (the undo window has closed), for these [addresses] on both
     * SIMs. The snackbar names them and offers "View rule" (BR-49).
     */
    data class AllowRulesAdded(val addresses: List<String>) : BlockedEvent
}

/** Groups messages by sender; groups and their messages are sorted newest first. */
fun groupBySender(messages: List<BlockedMessage>): List<SenderGroup> = messages
    .groupBy { it.address }
    .map { (address, list) -> SenderGroup(address, list.sortedByDescending { it.receivedAt }) }
    .sortedByDescending { it.latest.receivedAt }

/**
 * The Blocked tab and the sender thread. [ignoreFilter] is set for the thread: it shows (and acts on) every held
 * message of its sender, whatever chip the tab has chosen.
 */
class BlockedViewModel(
    application: Application,
    private val repository: SpamRepository,
    private val restoredLog: RestoredLog = RestoredLog.get(application),
    private val ignoreFilter: Boolean = false,
) : AndroidViewModel(application) {

    private val selected = MutableStateFlow<Set<String>>(emptySet())
    private val sims = MutableStateFlow<List<SimInfo>>(emptyList())

    /** The chip selection survives leaving the screen; a failing preferences read just means "All". */
    private val filter = MutableStateFlow(
        runCatching {
            BlockedFilters.decode(application.getSharedPreferences(FILTER_PREFS, 0).getString(FILTER_KEY, null))
        }.getOrDefault(BlockedFilter.All),
    )

    /** The ids hidden while an undo window is open, and whether any window is open. */
    private data class Pending(val ids: List<Long>, val any: Boolean)

    private data class Aux(
        val settings: SpamSettings,
        val rules: List<SpamRule>,
        val prompt: AllowPrompt?,
        val filter: BlockedFilter,
        val restored: List<RestoredEntry>,
    )

    /** Deletions waiting for the undo window to close, by token. Hidden from the list meanwhile. */
    private val pendingDeletes = MutableStateFlow<Map<Long, List<Long>>>(emptyMap())

    /**
     * "Not spam" and "Release once" actions that can still be undone, by token. The messages are put in the inbox
     * at once ([startRestore]); their rows stay hidden until Undo puts them back or the undo window closes.
     */
    private val pendingRestores = MutableStateFlow<Map<Long, PendingRestore>>(emptyMap())

    private val allowPrompt = MutableStateFlow<AllowPrompt?>(null)
    private var nextToken = 1L

    /** Restores being committed or undone, so the snackbar and the undo-window timer cannot run one twice. */
    private val committing = mutableSetOf<Long>()

    /** Restoring, allowing, undoing and committing run one at a time, so Undo always sees what it reverses. */
    private val restoreWork = Mutex()

    /** Set when the screen is gone for good: a restore that finishes later then cleans up after itself. */
    @Volatile private var cleared = false

    /**
     * [allow] is null until the user answers the "Always allow?" prompt; [allowSettled] once the answer's rules are
     * stored (or failed). [receipts] is what the repository did with each message, null while it is still at work.
     * The Undo snackbar ([announced]) waits for both.
     */
    private data class PendingRestore(
        val ids: List<Long>,
        val addresses: List<String>,
        val allow: Boolean? = null,
        /** Sender and arrival time by id, for the restored list; the rows are gone once restored. */
        val messages: Map<Long, BlockedMessage> = emptyMap(),
        val allowSettled: Boolean = allow == false,
        val receipts: Map<Long, RestoreReceipt>? = null,
        /** The restored-list entries written for this restore, so Undo can take them out again. */
        val logged: List<RestoredEntry> = emptyList(),
        /** The rules "Always allow" added, so Undo can remove them; null when none were added. */
        val allowChange: SenderBlocking.AllowChange? = null,
        val announced: Boolean = false,
    ) {
        private fun count(vararg outcomes: RestoreOutcome) = receipts.orEmpty().values.count { it.outcome in outcomes }

        val toInbox: Int get() = count(RestoreOutcome.RESTORED, RestoreOutcome.TEXT_ONLY)
        val mediaLost: Int get() = count(RestoreOutcome.TEXT_ONLY, RestoreOutcome.NOTHING)

        /** Kept picture messages the inbox did not take. They are still in Blocked. */
        val failed: Int
            get() = receipts.orEmpty().values.count { it.outcome == RestoreOutcome.FAILED && it.message != null }

        /** All rows while the restore runs; afterwards a message that could not be restored shows again. */
        val hidden: List<Long>
            get() = receipts?.let { r -> ids.filter { r[it]?.outcome != RestoreOutcome.FAILED } } ?: ids

        val ready: Boolean get() = !announced && allowSettled && receipts != null

        fun event(token: Long) =
            BlockedEvent.Restored(token, toInbox, if (allowChange != null) addresses.size else 0, mediaLost)
    }

    private val events = Channel<BlockedEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    val state: StateFlow<BlockedUiState> = combine(
        repository.blocked,
        repository.stats,
        combine(repository.settings, repository.rules, allowPrompt, filter, restoredLog.entries, ::Aux),
        combine(pendingDeletes, pendingRestores) { deletes, restores ->
            Pending(
                ids = deletes.values.flatten() + restores.values.flatMap { it.hidden },
                any = deletes.isNotEmpty() || restores.isNotEmpty(),
            )
        },
        combine(selected, sims) { s, i -> s to i },
    ) { blocked, stats, aux, pending, (sel, simList) ->
        val hidden = pending.ids.toSet()
        val held = blocked.filter { it.id !in hidden }
        // A SIM chip is only offered with two SIMs; a stored SIM choice that is gone falls back to All.
        val simIds = if (simList.size >= 2) simList.map { it.subscriptionId } else emptyList()
        val chosen = aux.filter
        val active = when {
            ignoreFilter -> BlockedFilter.All
            chosen is BlockedFilter.Sim && chosen.subscriptionId !in simIds -> BlockedFilter.All
            else -> chosen
        }
        val groups = groupBySender(BlockedFilters.apply(active, held))
        val keys = groups.map { it.address }.toSet()
        BlockedUiState(
            loading = false,
            groups = groups,
            filter = active,
            filterCounts = BlockedFilters.counts(held, simIds),
            heldCount = held.size,
            stats = stats,
            selected = sel intersect keys,
            sims = simList,
            mode = aux.settings.mode,
            rules = aux.rules.associateBy { it.id },
            purgeAfterDays = aux.settings.purgeAfterDays,
            allowPrompt = aux.prompt,
            restored = aux.restored,
            hasPendingUndo = pending.any,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BlockedUiState())

    init {
        viewModelScope.launch {
            sims.value = withContext(Dispatchers.IO) { SimDirectory.load(getApplication()) }
        }
        viewModelScope.launch { runCatching { repository.purgeExpired() } }
    }

    /**
     * Changes the unknown-sender mode through the same repository call Spam settings uses, then offers Undo. The
     * caller confirms before [UnknownSenderMode.OFF].
     */
    fun setMode(mode: UnknownSenderMode) {
        val previous = state.value.mode
        if (mode == previous) return
        viewModelScope.launch {
            runCatching { repository.updateSettings { it.copy(mode = mode) } }
                .onSuccess { events.trySend(BlockedEvent.ModeChanged(previous, mode)) }
                .onFailure { events.trySend(BlockedEvent.Failed) }
        }
    }

    /** Undo of [setMode]: puts the old mode back without another snackbar. */
    fun undoMode(previous: UnknownSenderMode) {
        viewModelScope.launch {
            runCatching { repository.updateSettings { it.copy(mode = previous) } }
                .onFailure { events.trySend(BlockedEvent.Failed) }
        }
    }

    /** Picks a chip under the banner (ID-14) and remembers it. */
    fun setFilter(value: BlockedFilter) {
        if (ignoreFilter || filter.value == value) return
        filter.value = value
        selected.value = emptySet()
        runCatching {
            getApplication<Application>().getSharedPreferences(FILTER_PREFS, 0)
                .edit().putString(FILTER_KEY, BlockedFilters.encode(value)).apply()
        }
    }

    /**
     * "Release once" (ID-16): puts this one message in the inbox without allowing the sender. It is the restore
     * "Not spam" does, minus the "Always allow?" question, with the same Undo.
     */
    fun releaseOnce(messageId: Long) {
        // A double tap can arrive before the state hides the message; the pending list is the up-to-date one (L4).
        if (messageId in pendingIds()) return
        val message = state.value.groups.firstNotNullOfOrNull { g -> g.messages.firstOrNull { it.id == messageId } }
            ?: return
        val token = nextToken++
        val restore = PendingRestore(
            listOf(message.id), emptyList(), allow = false, messages = mapOf(message.id to message),
        )
        pendingRestores.update { it + (token to restore) }
        startRestore(token)
    }

    fun toggleSelected(address: String) = selected.update { if (address in it) it - address else it + address }
    fun clearSelection() { selected.value = emptySet() }
    fun selectAll() { selected.value = state.value.groups.map { it.address }.toSet() }

    /**
     * "Not spam": puts the senders' messages in the inbox right away and asks whether to always allow them
     * ([BlockedUiState.allowPrompt]). Restoring only puts the messages in the inbox; it does not whitelist anyone
     * (T12). The Undo snackbar follows the answer and covers the restore and the rules.
     */
    fun notSpam(addresses: Set<String>) {
        val groups = state.value.groups.filter { it.address in addresses }
        // Messages already waiting on a restore are left out, so a second swipe cannot queue them twice (L4).
        val pending = pendingIds()
        val messages = groups.flatMap { it.messages }.filter { it.id !in pending }
        val ids = messages.map { it.id }
        if (ids.isEmpty()) return
        allowPrompt.value?.let { answerAllow(it.token, allow = false) }
        val token = nextToken++
        val senders = groups.map { it.address }
        val restore = PendingRestore(ids, senders, messages = messages.associateBy { it.id })
        pendingRestores.update { it + (token to restore) }
        selected.value = emptySet()
        val similar = senders.singleOrNull()?.let { SimilarRuleSuggestion.suggest(it, messages.first().body) }
        allowPrompt.value =
            AllowPrompt(token, senders, senders.filter(SenderBlocking::isSpoofable), ids.size, similar)
        startRestore(token)
    }

    /** I22: the "Also allow messages like this" box of the prompt. */
    fun setIncludeSimilar(token: Long, include: Boolean) =
        allowPrompt.update { prompt -> prompt?.takeIf { it.token == token }?.copy(includeSimilar = include) ?: prompt }

    /**
     * The answer to the "Always allow?" prompt. Yes stores the allow rules at once, so closing the app keeps them;
     * never answering just adds none. The Undo snackbar follows and covers the restore and the rules.
     */
    fun answerAllow(token: Long, allow: Boolean) {
        val similar = allowPrompt.value?.takeIf { it.token == token && it.includeSimilar }?.similar
        if (allowPrompt.value?.token == token) allowPrompt.value = null
        val pending = pendingRestores.value[token] ?: return
        if (pending.allow != null) return
        updateRestore(token) { it.copy(allow = allow, allowSettled = !allow) }
        if (!allow) {
            announceIfReady(token)
            return
        }
        viewModelScope.launch {
            val change = withContext(NonCancellable) {
                restoreWork.withLock {
                    runCatching { SenderBlocking.alwaysAllow(repository, pending.addresses, similar) }
                }
            }
            if (change.isFailure) events.trySend(BlockedEvent.Failed)
            updateRestore(token) { it.copy(allowSettled = true, allowChange = change.getOrNull()) }
            announceIfReady(token)
        }
    }

    /**
     * Puts the messages in the inbox now. Waiting for the undo window (as this once did) kept the restore only in
     * memory, so closing the app within it, or before answering "Always allow?", lost it. The repository restores
     * each message crash-safely, and the work is not cancelled with the screen; Undo reverses it instead.
     */
    private fun startRestore(token: Long) {
        val pending = pendingRestores.value[token] ?: return
        val messages = pending.ids.mapNotNull { pending.messages[it] }
        viewModelScope.launch {
            withContext(NonCancellable) {
                val (receipts, logged) = restoreWork.withLock { restore(messages) }
                // The chat list (MainActivity) reloads on this event, so the restored text shows without a refresh.
                refreshConversations()
                if (cleared) {
                    receipts.values.forEach { runCatching { repository.finishRestore(it) } }
                } else {
                    updateRestore(token) { it.copy(receipts = receipts, logged = logged) }
                    announceIfReady(token)
                }
            }
        }
    }

    private suspend fun restore(messages: List<BlockedMessage>): Pair<Map<Long, RestoreReceipt>, List<RestoredEntry>> {
        var error = false
        val receipts = messages.associate { message ->
            message.id to runCatching { repository.restore(message.id) }.getOrElse {
                error = true
                RestoreReceipt(null, RestoreOutcome.FAILED)
            }
        }
        if (error) events.trySend(BlockedEvent.Failed)
        val now = System.currentTimeMillis()
        val inInbox = setOf(RestoreOutcome.RESTORED, RestoreOutcome.TEXT_ONLY)
        val logged = messages
            .filter { receipts[it.id]?.outcome in inInbox }
            .map { RestoredEntry(it.address, it.receivedAt, now) }
        restoredLog.record(logged)
        return receipts to logged
    }

    /** Shows the Undo snackbar once the restore is done and the allow answer settled, and starts the undo window. */
    private fun announceIfReady(token: Long) {
        val pending = pendingRestores.value[token]?.takeIf { it.ready } ?: return
        if (pending.toInbox == 0 && pending.mediaLost == 0 && pending.allowChange == null) {
            // Nothing left Blocked and no rule was added: there is nothing to undo, only a failure to report.
            pendingRestores.update { it - token }
            if (pending.failed > 0) events.trySend(BlockedEvent.RestoreFailed(pending.failed))
            return
        }
        updateRestore(token) { it.copy(announced = true) }
        events.trySend(pending.event(token))
        // The snackbar normally ends the undo window, but it is cancelled unanswered when the user leaves the tab.
        // Close the window on our own once it has passed.
        viewModelScope.launch {
            delay(UNDO_WINDOW_MS)
            commitRestore(token)
        }
    }

    /** Undo: takes the messages back out of the inbox into Blocked, and removes the rules "Always allow" added. */
    fun undoRestore(token: Long) {
        val pending = pendingRestores.value[token]?.takeIf { it.announced } ?: return
        if (!committing.add(token)) return
        viewModelScope.launch {
            val undone = withContext(NonCancellable) { restoreWork.withLock { undo(pending) } }
            if (!undone) events.trySend(BlockedEvent.Failed)
            pendingRestores.update { it - token }
            committing -= token
        }
    }

    /** False when something could not be taken back; what could not stays restored, so nothing is lost. */
    private suspend fun undo(pending: PendingRestore): Boolean {
        val receipts = pending.receipts.orEmpty().values
            .filter { it.outcome != RestoreOutcome.FAILED && it.outcome != RestoreOutcome.NOT_FOUND }
        val (back, stayed) = receipts.partition { runCatching { repository.undoRestore(it) }.getOrDefault(false) }
        val rulesBack = pending.allowChange
            ?.let { runCatching { SenderBlocking.undoAllow(repository, it) }.isSuccess } ?: true
        // Messages still in the inbox stay listed as restored, and their kept files are no longer needed.
        val returned = back.mapNotNull { it.message }.map { it.address to it.receivedAt }.toSet()
        restoredLog.remove(pending.logged.filter { (it.address to it.receivedAt) in returned })
        stayed.forEach { runCatching { repository.finishRestore(it) } }
        refreshConversations()
        return stayed.isEmpty() && rulesBack
    }

    /** The undo window closed: the restore and the rules stay, and the kept files the inbox copy has go. */
    fun commitRestore(token: Long) {
        val pending = pendingRestores.value[token]?.takeIf { it.announced } ?: return
        if (!committing.add(token)) return
        viewModelScope.launch {
            withContext(NonCancellable) {
                restoreWork.withLock {
                    pending.receipts.orEmpty().values.forEach { runCatching { repository.finishRestore(it) } }
                }
            }
            if (pending.allowChange != null) events.trySend(BlockedEvent.AllowRulesAdded(pending.addresses))
            if (pending.failed > 0) events.trySend(BlockedEvent.RestoreFailed(pending.failed))
            pendingRestores.update { it - token }
            committing -= token
        }
    }

    private fun updateRestore(token: Long, change: (PendingRestore) -> PendingRestore) =
        pendingRestores.update { map -> map[token]?.let { map + (token to change(it)) } ?: map }

    /** Hides the messages now and deletes them for real once the undo snackbar goes away. */
    fun delete(addresses: Set<String>) {
        hideAndDelete(idsOf(addresses))
        selected.value = emptySet()
    }

    /** Delete of single messages from the sender thread, with the same Undo as [delete]. */
    fun deleteMessages(messageIds: Set<Long>) {
        val pending = pendingIds()
        val held = state.value.groups.flatMap { g -> g.messages.map { it.id } }.toSet()
        hideAndDelete(messageIds.filter { it in held && it !in pending })
    }

    private fun hideAndDelete(ids: List<Long>) {
        if (ids.isEmpty()) return
        val token = nextToken++
        pendingDeletes.update { it + (token to ids) }
        events.trySend(BlockedEvent.Deleted(token, ids.size))
    }

    fun undoDelete(token: Long) = pendingDeletes.update { it - token }

    fun commitDelete(token: Long) {
        val ids = pendingDeletes.value[token] ?: return
        viewModelScope.launch {
            runCatching { repository.deleteBlocked(ids) }.onFailure { events.trySend(BlockedEvent.Failed) }
            pendingDeletes.update { it - token }
        }
    }

    /**
     * Shows the Undo snackbar again for every action still waiting on one. Call when the screen starts collecting
     * [eventFlow]: after a rotation the old snackbar was cancelled without an answer, which left the rows hidden with
     * no way to undo or finish the action. Events still queued for the old screen are folded in, so none shows twice.
     * A restore still waiting on the allow answer has no snackbar yet; its prompt lives in the state.
     */
    fun replayPending() {
        val queued = generateSequence { events.tryReceive().getOrNull() }.toList()
        queued.filter { it is BlockedEvent.Failed || it is BlockedEvent.RestoreFailed }.forEach { events.trySend(it) }
        val replays = pendingRestores.value.filter { it.value.announced && it.key !in committing }
            .map { (token, r) -> token to r.event(token) } +
            pendingDeletes.value.map { (token, ids) -> token to BlockedEvent.Deleted(token, ids.size) }
        replays.sortedBy { it.first }.forEach { events.trySend(it.second) }
    }

    override fun onCleared() {
        // Leaving the screen finalises deletions the user did not undo. Restores are done already; what is left is
        // deleting the kept files of those whose undo window was still open (one still running does that itself, and
        // one being committed or undone finishes on its own). An unanswered allow prompt adds no rule.
        cleared = true
        val leftovers = pendingDeletes.value.values.flatten()
        val finished = pendingRestores.value.filterKeys { it !in committing }.values
            .flatMap { it.receipts.orEmpty().values }
        if (leftovers.isNotEmpty() || finished.isNotEmpty()) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                finished.forEach { runCatching { repository.finishRestore(it) } }
                if (leftovers.isNotEmpty()) runCatching { repository.deleteBlocked(leftovers) }
            }
        }
        super.onCleared()
    }

    /** Ids hidden while a restore or a delete waits for its undo window. */
    private fun pendingIds(): Set<Long> =
        (pendingRestores.value.values.flatMap { it.hidden } + pendingDeletes.value.values.flatten()).toSet()

    private fun idsOf(addresses: Set<String>): List<Long> =
        state.value.groups.filter { it.address in addresses }.flatMap { g -> g.messages.map { it.id } }

    companion object {
        /** A little longer than a Long snackbar (10 s), so the snackbar's own answer normally comes first. */
        const val UNDO_WINDOW_MS = 11_000L

        private const val FILTER_PREFS = "spamless_blocked_ui"
        private const val FILTER_KEY = "filter"

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as Application
                BlockedViewModel(app, SpamRepositoryProvider.get(app))
            }
        }

        /** The sender thread's model: every held message of the sender, whatever the tab's chip. */
        val ThreadFactory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as Application
                BlockedViewModel(app, SpamRepositoryProvider.get(app), ignoreFilter = true)
            }
        }
    }
}
