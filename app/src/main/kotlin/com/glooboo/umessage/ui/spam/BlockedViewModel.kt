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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.glooboo.umessage.helpers.refreshConversations
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.spam.data.SpamAutoDelete
import com.glooboo.umessage.spam.data.SpamProviderMigration
import kotlinx.coroutines.withContext
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.RestoreOutcome
import com.glooboo.umessage.spam.RestoreReceipt
import com.glooboo.umessage.spam.SpamRepository
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.SpamStats
import com.glooboo.umessage.spam.UnknownSenderMode
import com.glooboo.umessage.ui.conversations.MarkReadProgress

/**
 * All blocked messages of one sender, newest first. The sender address is the group key: the newest message's address
 * as it was written. Messages written in another form of the same number (I20) share the group, so a screen that
 * was given any of those addresses still finds it with [hasAddress].
 */
data class SenderGroup(val address: String, val messages: List<BlockedMessage>) {
    val latest: BlockedMessage get() = messages.first()

    /** How many of the sender's held messages were not opened yet. */
    val unreadCount: Int get() = messages.count { it.unread }

    /** Any held message is unread: the row is drawn bold like an unread chat on the Messages tab. */
    val unread: Boolean get() = unreadCount > 0

    /** Whether [other] is this group's key or the address of one of its messages. */
    fun hasAddress(other: String): Boolean = address == other || messages.any { it.address == other }
}

/** What a "Not spam" from a blocked chat did: how many messages reached the inbox, so the screen can open the chat. */
data class NotSpamDone(val addresses: List<String>, val toInbox: Int, val allowed: List<String> = emptyList())

data class BlockedUiState(
    val loading: Boolean = true,
    val groups: List<SenderGroup> = emptyList(),
    val stats: SpamStats = SpamStats(0, 0, 0),
    val selected: Set<String> = emptySet(),
    val sims: List<SimInfo> = emptyList(),
    /**
     * Current unknown-sender mode, shown on the stats card with a shortcut to the spam settings. With the provider
     * truth flag on it is the mode the filter switch draws as (shownMode), for the card and the contacts hint.
     */
    val mode: UnknownSenderMode = UnknownSenderMode.SMART,
    /** Rules by id, so each blocked message can say which rule blocked it (SU-22). */
    val rules: Map<Long, SpamRule> = emptyMap(),
    /** Blocked messages older than this many days are deleted; 0 keeps them (SU-23). */
    val purgeAfterDays: Int = 30,
    /** The chip chosen under the banner (ID-14); [groups] only holds what it lets through. */
    val filter: BlockedFilter = BlockedFilter.All,
    /** The text typed in the search box (spamui-20); [groups] only holds the senders that answer it. */
    val query: String = "",
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
    /** A "Not spam" is moving messages one by one, over all screens: how far it is. Null when nothing moves. */
    val moveProgress: MarkReadProgress? = null,
    /** The messages being moved, by sender. The sender page draws them dimmed until the move is over. */
    val movingGroups: List<SenderGroup> = emptyList(),
    /** Clock time the running move began, for the 300 ms rule of the indicator; 0 when nothing moves. */
    val moveStartedAt: Long = 0L,
) {
    val selecting: Boolean get() = selected.isNotEmpty()
}

sealed interface BlockedEvent {
    /**
     * Shown with an Undo action. The messages are in the inbox already (and the allow rules stored for [senders],
     * when [allowed] > 0); Undo takes both back. [count] messages went to the inbox; [mediaLost] of the picture
     * messages among all restored ones lost their pictures (never downloaded, download failed or expired), which
     * the snackbar says plainly (SU-17). Picture messages with neither pictures nor text are not in [count]:
     * nothing of them reached the inbox.
     */
    data class Restored(
        val token: Long,
        val count: Int,
        val allowed: Int = 0,
        val mediaLost: Int = 0,
        val senders: List<String> = emptyList(),
    ) : BlockedEvent

    /** [count] kept picture messages could not be put in the inbox. They are still in Blocked. */
    data class RestoreFailed(val count: Int) : BlockedEvent

    /** Shown with an Undo action; the deletion becomes final unless the user chooses Undo. */
    data class Deleted(val token: Long, val count: Int) : BlockedEvent
    data object Failed : BlockedEvent

    /** The unknown-sender mode was changed from this screen; Undo puts [previous] back (P1-*). */
    data class ModeChanged(val previous: UnknownSenderMode, val mode: UnknownSenderMode) : BlockedEvent
}

/**
 * Groups messages by sender; groups and their messages are sorted newest first. [keyOf] says which addresses are one
 * sender (I20); the group is named by its newest message's address.
 */
fun groupBySender(messages: List<BlockedMessage>, keyOf: (String) -> String = { it }): List<SenderGroup> = messages
    .groupBy { keyOf(it.address) }
    .map { (_, list) ->
        val newestFirst = list.sortedByDescending { it.receivedAt }
        SenderGroup(newestFirst.first().address, newestFirst)
    }
    .sortedByDescending { it.latest.receivedAt }

/** The order messages are moved in: oldest first, so the chat gets them in the order they arrived. */
private val OLDEST_FIRST = compareBy<BlockedMessage> { it.receivedAt }.thenBy { it.id }

/**
 * The Blocked tab and the sender thread. [ignoreFilter] is set for the thread: it shows (and acts on) every held
 * message of its sender, whatever chip the tab has chosen.
 */
class BlockedViewModel(
    application: Application,
    private val repository: SpamRepository,
    private val restoredLog: RestoredLog = RestoredLog.get(application),
    private val ignoreFilter: Boolean = false,
    private val moves: NotSpamMoves = NotSpamMoves.shared,
) : AndroidViewModel(application) {

    private val senderKeys = SenderKeys.forDevice(application)
    private val selected = MutableStateFlow<Set<String>>(emptySet())
    private val sims = MutableStateFlow<List<SimInfo>>(emptyList())

    /** The search box's text (spamui-20). Kept while the screen is alive, not stored; the thread ignores it. */
    private val query = MutableStateFlow("")

    /** How often the tab was left; a "search open" saved before the last leave is stale (the query was cleared). */
    var tabLeaves = 0

    /** The chip selection survives leaving the screen; a failing preferences read just means "All". */
    private val filter = MutableStateFlow(
        runCatching {
            BlockedFilters.decode(application.getSharedPreferences(FILTER_PREFS, 0).getString(FILTER_KEY, null))
        }.getOrDefault(BlockedFilter.All),
    )

    /** The ids hidden while an undo window is open, and whether any window is open. */
    private data class Pending(val ids: List<Long>, val any: Boolean, val move: MoveState)

    private data class Aux(
        val settings: SpamSettings,
        val rules: List<SpamRule>,
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

    /** Tokens of "Delete all" deletions that also clear the restored list once they are final. */
    private val clearLogTokens = mutableSetOf<Long>()
    private var nextToken = 1L

    /** Restores being committed or undone, so the snackbar and the undo-window timer cannot run one twice. */
    private val committing = mutableSetOf<Long>()

    /** Restoring, allowing, undoing and committing run one at a time, so Undo always sees what it reverses. */
    private val restoreWork = Mutex()

    /** Set when the screen is gone for good: a restore that finishes later then cleans up after itself. */
    @Volatile private var cleared = false

    /**
     * [allowAddresses] are the senders whose allow rules go with the restore: none for "Release once" and none for
     * names anyone can fake. [receipts] is what the repository did with each message, null while it is still at
     * work; the Undo snackbar ([announced]) waits for them. A [silent] restore comes from a blocked chat: it shows
     * no Undo snackbar, and the screen opens the normal chat instead.
     */
    private data class PendingRestore(
        val ids: List<Long>,
        val addresses: List<String>,
        val allowAddresses: List<String> = emptyList(),
        val silent: Boolean = false,
        /** Sender and arrival time by id, for the restored list; the rows are gone once restored. */
        val messages: Map<Long, BlockedMessage> = emptyMap(),
        val receipts: Map<Long, RestoreReceipt>? = null,
        /** The restored-list entries written for this restore, so Undo can take them out again. */
        val logged: List<RestoredEntry> = emptyList(),
        /** The rules the restore added, so Undo can remove them; null when none were added. */
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
        val claimed: List<Long>
            get() = receipts?.let { r -> ids.filter { r[it]?.outcome != RestoreOutcome.FAILED } } ?: ids

        val ready: Boolean get() = !announced && receipts != null

        fun event(token: Long): BlockedEvent.Restored {
            val allowed = if (allowChange != null) allowAddresses else emptyList()
            return BlockedEvent.Restored(token, toInbox, allowed.size, mediaLost, allowed)
        }
    }

    /** What the repository and the rules did for one restore. */
    private class RestoreWork(
        val receipts: Map<Long, RestoreReceipt>,
        val logged: List<RestoredEntry>,
        val change: SenderBlocking.AllowChange?,
    )

    private val events = Channel<BlockedEvent>(Channel.BUFFERED)
    val eventFlow = events.receiveAsFlow()

    private val doneState = MutableStateFlow<NotSpamDone?>(null)

    /**
     * The "Not spam" started with `openChat` whose chat is still to open, once its messages are in the inbox (or
     * failed to be, and the question about names, if any, is answered). It is state, not a one-shot event: a screen
     * recreated meanwhile (rotation) still gets it, until the screen that opens the chat calls [notSpamHandled].
     */
    val notSpamDone: Flow<NotSpamDone> = doneState.filterNotNull()

    /** The same as [notSpamDone] but as state: null again once [notSpamHandled] ran, so a page can stop waiting. */
    val notSpamPending: StateFlow<NotSpamDone?> = doneState

    /** The chat for [done] is opening (or was given up on): forget it, so no screen opens it twice. */
    fun notSpamHandled(done: NotSpamDone) {
        doneState.compareAndSet(done, null)
    }

    private val namePromptState = MutableStateFlow<NameAllowPrompt?>(null)

    /**
     * The "Always allow this name?" question after a "Not spam" of senders that go by a name. It lives here, so a
     * rotation neither loses nor repeats it; an answer ([allowPromptedNames], [dismissNamePrompt]) clears it. It is
     * not saved over process death: the dialog is gone then, and a blocked chat does not open the chat (rare).
     */
    val namePrompt: StateFlow<NameAllowPrompt?> = namePromptState

    /** Restores whose Undo snackbar (or chat) waits for the answer to the question, so the dialog cannot hide it. */
    private val waitingForAnswer = mutableSetOf<Long>()

    /** What to do once the allow-only snackbar of a token is over (Undo or not): a blocked chat opens its chat. */
    private val afterAllow = mutableMapOf<Long, () -> Unit>()

    val state: StateFlow<BlockedUiState> = combine(
        repository.blocked,
        repository.stats,
        combine(repository.settings, repository.rules, filter, restoredLog.entries, ::Aux),
        combine(pendingDeletes, pendingRestores, moves.state) { deletes, restores, move ->
            // The list hides everything that moves at once, so the rows leave together and not one by one. The
            // sender page keeps them, dimmed, until the move is over (a restore still running hides nothing there).
            val restoreIds = restores.values.filterNot { ignoreFilter && it.receipts == null }.flatMap { it.claimed }
            val moveIds = if (ignoreFilter) emptySet() else move.inFlight.keys
            Pending(
                ids = deletes.values.flatten() + restoreIds + moveIds,
                any = deletes.isNotEmpty() || restores.isNotEmpty(),
                move = move,
            )
        },
        combine(selected, sims, query) { s, i, q -> Triple(s, i, q) },
    ) { blocked, stats, aux, pending, (sel, simList, typed) ->
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
        val found = groupBySender(BlockedFilters.apply(active, held), senderKeys::keyOf)
        val groups = if (ignoreFilter) found else BlockedFilters.searchGroups(found, typed)
        val keys = groups.map { it.address }.toSet()
        BlockedUiState(
            loading = false,
            groups = groups,
            filter = active,
            query = if (ignoreFilter) "" else typed,
            filterCounts = BlockedFilters.counts(held, simIds, senderKeys::keyOf),
            heldCount = held.size,
            stats = stats,
            selected = sel intersect keys,
            sims = simList,
            mode = aux.settings.shownMode(),
            rules = aux.rules.associateBy { it.id },
            purgeAfterDays = aux.settings.purgeAfterDays,
            restored = aux.restored,
            hasPendingUndo = pending.any,
            moveProgress = if (pending.move.running) MarkReadProgress(pending.move.done, pending.move.total) else null,
            movingGroups = groupBySender(pending.move.inFlight.values.toList(), senderKeys::keyOf),
            moveStartedAt = pending.move.startedAt,
        )
    }.flowOn(Dispatchers.Default) // grouping, filtering and the link checks stay off the main thread
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BlockedUiState())

    init {
        viewModelScope.launch {
            sims.value = withContext(Dispatchers.IO) { SimDirectory.load(getApplication()) }
        }
        viewModelScope.launch {
            // Provider truth (step F): waiting held messages are moved and auto-delete acts on the phone's store; the
            // repository purge then deletes no held row (only the file sweep runs).
            if (ProviderTruthFlag.enabled) {
                withContext(Dispatchers.IO) {
                    runCatching { SpamProviderMigration.runPending(getApplication<Application>()) }
                    runCatching { SpamAutoDelete.run(getApplication<Application>()) }
                }
            }
            runCatching { repository.purgeExpired() }
            // The restored list goes with the blocked messages the purge deletes (spamui-15): its entries live no
            // longer than the purge setting, and 30 days at most.
            runCatching {
                val days = repository.settings.first().purgeAfterDays
                if (days > 0) restoredLog.dropOlderThan(days)
            }
        }
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

    /** The search box changed (spamui-20): the list keeps the senders whose messages or number hold [text]. */
    fun setQuery(text: String) {
        if (ignoreFilter || query.value == text) return
        query.value = text
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
     * "Not spam" does, minus the allow rule, with the same Undo.
     */
    fun releaseOnce(messageId: Long) = releaseMessages(setOf(messageId))

    /**
     * "Move to inbox" for the messages chosen in the sender thread (spamui-11): one restore for all of them, without
     * allowing the sender, with a single Undo.
     */
    fun releaseMessages(messageIds: Set<Long>) {
        // A double tap can arrive before the state hides the message; the pending list is the up-to-date one (L4).
        val pending = pendingIds()
        val messages = state.value.groups.flatMap { it.messages }
            .filter { it.id in messageIds && it.id !in pending }
        if (messages.isEmpty()) return
        val token = nextToken++
        val restore = PendingRestore(
            messages.sortedWith(OLDEST_FIRST).map { it.id }, emptyList(), messages = messages.associateBy { it.id },
        )
        pendingRestores.update { it + (token to restore) }
        startRestore(token)
    }

    /**
     * The sender's thread is open: its held messages count as read, so the row on the tab is no longer bold. Only
     * messages still unread are written, and the state follows from the repository's flow.
     */
    fun markRead(address: String) {
        val ids = state.value.groups.filter { it.hasAddress(address) }
            .flatMap { g -> g.messages.filter { it.unread }.map { it.id } }
        if (ids.isEmpty()) return
        viewModelScope.launch { runCatching { repository.markBlockedRead(ids) } }
    }

    fun toggleSelected(address: String) = selected.update { if (address in it) it - address else it + address }
    fun clearSelection() { selected.value = emptySet() }
    fun selectAll() { selected.value = state.value.groups.map { it.address }.toSet() }

    /**
     * "Not spam": in one step, puts the senders' messages in the inbox and stores an allow rule for each sender
     * (a number rule, or a sender-id rule), then offers one Undo for both. Senders whose names anyone can fake get no
     * rule: their messages only move, and the "Always allow this name?" question follows (the Undo waits for its
     * answer). All rows leave the list at once (on the sender page they stay
     * dimmed until the chat opens), and the messages move oldest first; a message that fails to move comes back.
     * With [openChat] no snackbar is shown, and [notSpamDone] tells the blocked chat to open the normal chat.
     * Returns false when there was nothing to move (all of it is already on its way).
     */
    fun notSpam(addresses: Set<String>, openChat: Boolean = false): Boolean {
        val groups = state.value.groups.filter { g -> addresses.any(g::hasAddress) }
        // Messages already waiting on a restore are left out, so a second swipe cannot queue them twice (L4).
        val pending = pendingIds()
        val messages = groups.flatMap { it.messages }.filter { it.id !in pending }
        val ids = messages.sortedWith(OLDEST_FIRST).map { it.id }
        if (ids.isEmpty()) return false
        val token = nextToken++
        val senders = groups.map { it.address }
        val restore = PendingRestore(
            ids, senders, senders.filterNot(SenderBlocking::isSpoofable), openChat,
            messages = messages.associateBy { it.id },
        )
        pendingRestores.update { it + (token to restore) }
        selected.value = emptySet()
        startRestore(token)
        return true
    }

    /**
     * Puts the messages in the inbox now, with the allow rules. Waiting for the undo window (as this once did) kept
     * the restore only in memory, so closing the app within it lost it. The repository restores each message
     * crash-safely, and the work is not cancelled with the screen; Undo reverses it instead.
     */
    private fun startRestore(token: Long) {
        val pending = pendingRestores.value[token] ?: return
        val messages = pending.ids.mapNotNull { pending.messages[it] }
        viewModelScope.launch {
            withContext(NonCancellable) {
                // In flight from here on, also while this batch waits for the lock behind another one.
                moves.begin(messages)
                val work = restoreWork.withLock { restoreAndAllow(messages, pending.allowAddresses) }
                // The chat list (MainActivity) reloads on this event, so the restored text shows without a refresh.
                refreshConversations()
                if (cleared) {
                    finishInBackground(work.receipts.values)
                    // The screen that asked is gone: the Spam list says the move is over.
                    val toInbox = work.receipts.values.count {
                        it.outcome == RestoreOutcome.RESTORED || it.outcome == RestoreOutcome.TEXT_ONLY
                    }
                    if (toInbox > 0) moves.announceOrphan(toInbox)
                } else {
                    updateRestore(token) {
                        it.copy(receipts = work.receipts, logged = work.logged, allowChange = work.change)
                    }
                    offerNameAllow(token, pending, work.receipts)
                    announceIfReady(token)
                }
            }
        }
    }

    /**
     * "Always allow" of the Why-blocked dialog (spamui-21): stores an allow rule for [address] through the same call
     * "Not spam" uses (the sender's own block rules go, as they would be dead weight), without moving any message.
     * The snackbar is the allow-only line of [BlockedEvent.Restored], and its Undo removes the rule again.
     */
    fun alwaysAllow(address: String) {
        if (SenderBlocking.isSpoofable(address)) return
        allowOnly(listOf(address))
    }

    /**
     * After "Not spam" moved texts of senders that go by a name, asks once whether to allow those names: only for names
     * whose messages really reached the inbox and that no allow rule names yet. Nothing is asked for "Release once"
     * and "Move to inbox" (no senders), nor when nothing moved. The answer is not remembered: the next "Not spam" asks
     * again.
     */
    private suspend fun offerNameAllow(token: Long, pending: PendingRestore, receipts: Map<Long, RestoreReceipt>) {
        if (pending.addresses.isEmpty()) return
        val inInbox = setOf(RestoreOutcome.RESTORED, RestoreOutcome.TEXT_ONLY)
        val movedFrom = pending.ids.filter { receipts[it]?.outcome in inInbox }
            .mapNotNull { pending.messages[it]?.address }
        val rules = runCatching { repository.rules.first() }.getOrNull() ?: return
        val names = namesToOffer(movedFrom, rules)
        if (names.isEmpty()) return
        namePromptState.update { old -> NameAllowPrompt(old?.byToken.orEmpty() + (token to names)) }
    }

    /**
     * Keeps the Undo snackbar of a restore back while the question about its names is open (the dialog covers the
     * snackbar, and its Undo window would run unseen). [answerQuestion] lets it go. True when it is kept back.
     */
    private fun waitsForAnswer(token: Long): Boolean {
        val asking = namePromptState.value?.tokens.orEmpty()
        return (token in asking).also { if (it) waitingForAnswer += token }
    }

    /**
     * Clears the question and lets the kept-back restores go on. With [allow] a sender-name rule is stored for each
     * asked name first. A restore with a snackbar (the Spam list) takes the rule into its own token, so its one
     * Undo reverses the move and the rule together. A restore from a blocked chat has no snackbar: it opens its chat
     * only after the rule's own Undo snackbar is over, so the user can still take the rule back.
     */
    private fun answerQuestion(allow: Boolean) {
        val prompt = namePromptState.value ?: return
        namePromptState.value = null
        val waiting = waitingForAnswer.toList()
        waitingForAnswer.clear()
        val (silent, loud) = waiting.partition { pendingRestores.value[it]?.silent == true }
        val firstLoud = loud.minOrNull()
        when {
            !allow -> (loud + silent).forEach(::announceIfReady)
            firstLoud != null -> viewModelScope.launch {
                withContext(NonCancellable) {
                    restoreWork.withLock {
                        // Each restore takes its own names, so its Undo removes only those rules. The names of a
                        // restore without a snackbar go to the first one that has it.
                        val quiet = silent.flatMap { prompt.byToken[it].orEmpty() }
                        loud.sorted().forEach { token ->
                            val own = prompt.byToken[token].orEmpty()
                            addAllowTo(token, if (token == firstLoud) own + quiet else own)
                        }
                    }
                }
                (loud + silent).forEach(::announceIfReady)
            }
            else -> allowOnly(prompt.names) { silent.forEach(::announceIfReady) }
        }
    }

    /** Stores the allow rules of [names] and hangs them on the restore [token], before its snackbar is shown. */
    private suspend fun addAllowTo(token: Long, names: List<String>) {
        val (fresh, change) = storeFreshAllow(names) ?: return
        updateRestore(token) { old ->
            val before = old.allowChange
            old.copy(
                allowAddresses = old.allowAddresses.takeIf { before != null }.orEmpty() + fresh,
                allowChange = SenderBlocking.AllowChange(
                    before?.addedRuleIds.orEmpty() + change.addedRuleIds,
                    before?.removedBlockRules.orEmpty() + change.removedBlockRules,
                ),
            )
        }
    }

    /** "Always allow" of the question: a sender-name allow rule for each asked name, with the same Undo snackbar. */
    fun allowPromptedNames() = answerQuestion(allow = true)

    /** "Not now" of the question: nothing changes and nothing is remembered. */
    fun dismissNamePrompt() = answerQuestion(allow = false)

    /** [afterwards] runs once the allow snackbar is over, or at once when no rule was stored. */
    private fun allowOnly(addresses: List<String>, afterwards: (() -> Unit)? = null) {
        val token = nextToken++
        if (afterwards != null) afterAllow[token] = afterwards
        viewModelScope.launch {
            val shown = withContext(NonCancellable) { restoreWork.withLock { storeAllow(token, addresses) } }
            if (!shown) {
                afterAllow.remove(token)?.invoke()
            } else if (pendingRestores.value.containsKey(token)) {
                delay(UNDO_WINDOW_MS)
                commitRestore(token)
            }
        }
    }

    /**
     * Stores the allow rules and offers their Undo. Names an allow rule lets through already are left out here, under
     * the lock, so a second answer cannot add a duplicate rule. False when no Undo window opened.
     */
    private suspend fun storeAllow(token: Long, addresses: List<String>): Boolean {
        val (fresh, change) = storeFreshAllow(addresses) ?: return false
        val pending = PendingRestore(
            ids = emptyList(), addresses = fresh, allowAddresses = fresh,
            receipts = emptyMap(), allowChange = change, announced = true,
        )
        pendingRestores.update { it + (token to pending) }
        events.trySend(pending.event(token))
        return true
    }

    /** The names and what storing their allow rules changed; null when none is new or the rules could not be stored. */
    private suspend fun storeFreshAllow(addresses: List<String>): Pair<List<String>, SenderBlocking.AllowChange>? {
        val rules = runCatching { repository.rules.first() }.getOrNull().orEmpty()
        val fresh = addresses.filterNot { isNameAllowed(it, rules) }
        if (fresh.isEmpty()) return null
        val change = runCatching { SenderBlocking.alwaysAllow(repository, fresh) }.getOrNull()
        if (change == null) events.trySend(BlockedEvent.Failed)
        return change?.let { fresh to it }
    }

    /** The restore and its allow rules as one piece of work; no rule is added when nothing reached the inbox. */
    private suspend fun restoreAndAllow(messages: List<BlockedMessage>, allow: List<String>): RestoreWork {
        val (receipts, logged) = restore(messages)
        val inInbox = setOf(RestoreOutcome.RESTORED, RestoreOutcome.TEXT_ONLY)
        val moved = receipts.values.any { it.outcome in inInbox }
        val change = if (allow.isEmpty() || !moved) {
            null
        } else {
            runCatching { SenderBlocking.alwaysAllow(repository, allow) }
                .onFailure { events.trySend(BlockedEvent.Failed) }
                .getOrNull()
        }
        return RestoreWork(receipts, logged, change)
    }

    private suspend fun restore(messages: List<BlockedMessage>): Pair<Map<Long, RestoreReceipt>, List<RestoredEntry>> {
        var error = false
        val inInbox = setOf(RestoreOutcome.RESTORED, RestoreOutcome.TEXT_ONLY)
        // spamui-1: a bigger batch shows its first restored message in the chat list at once, not only at the end.
        var firstShown = messages.size <= 1
        // The batch is over with the last message, before the allow rules: the move is not "running" any longer.
        val receipts = try {
            messages.associate { message ->
                val receipt = runCatching { repository.restore(message.id) }.getOrElse {
                    error = true
                    RestoreReceipt(null, RestoreOutcome.FAILED)
                }
                if (!firstShown && receipt.outcome in inInbox) {
                    firstShown = true
                    refreshConversations()
                }
                moves.step()
                message.id to receipt
            }
        } finally {
            moves.end(messages.map { it.id })
        }
        if (error) events.trySend(BlockedEvent.Failed)
        val now = System.currentTimeMillis()
        val logged = messages
            .filter { receipts[it.id]?.outcome in inInbox }
            .map { RestoredEntry(it.address, it.receivedAt, now) }
        restoredLog.record(logged)
        return receipts to logged
    }

    /** Shows the Undo snackbar once the restore is done, and starts the undo window. */
    private fun announceIfReady(token: Long) {
        val pending = pendingRestores.value[token]?.takeIf { it.ready } ?: return
        if (waitsForAnswer(token)) return
        if (pending.silent) {
            val allowed = if (pending.allowChange != null) pending.allowAddresses else emptyList()
            doneState.value = NotSpamDone(pending.addresses, pending.toInbox, allowed)
        }
        if (pending.silent && pending.toInbox > 0) {
            // The blocked chat opens the normal chat: nothing here to undo, so the restore is final right away.
            pendingRestores.update { it - token }
            finishInBackground(pending.receipts.orEmpty().values)
            if (pending.failed > 0) events.trySend(BlockedEvent.RestoreFailed(pending.failed))
            return
        }
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

    /** Ends the undo window of finished restores on a scope of their own, so closing the screen cannot cancel it. */
    private fun finishInBackground(receipts: Collection<RestoreReceipt>) {
        if (receipts.isEmpty()) return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            receipts.forEach { runCatching { repository.finishRestore(it) } }
        }
    }

    /** Undo: takes the messages back out of the inbox into Blocked, and removes the allow rules the restore added. */
    fun undoRestore(token: Long) {
        val pending = pendingRestores.value[token]?.takeIf { it.announced } ?: return
        if (!committing.add(token)) return
        namePromptState.update { prompt ->
            prompt?.let { NameAllowPrompt(it.byToken - token) }?.takeIf { it.byToken.isNotEmpty() }
        }
        viewModelScope.launch {
            val undone = withContext(NonCancellable) { restoreWork.withLock { undo(pending) } }
            if (!undone) events.trySend(BlockedEvent.Failed)
            pendingRestores.update { it - token }
            committing -= token
            afterAllow.remove(token)?.invoke()
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
            if (pending.failed > 0) events.trySend(BlockedEvent.RestoreFailed(pending.failed))
            pendingRestores.update { it - token }
            committing -= token
            afterAllow.remove(token)?.invoke()
        }
    }

    private fun updateRestore(token: Long, change: (PendingRestore) -> PendingRestore) =
        pendingRestores.update { map -> map[token]?.let { map + (token to change(it)) } ?: map }

    /** Hides the messages now and deletes them for real once the undo snackbar goes away. */
    fun delete(addresses: Set<String>) {
        hideAndDelete(idsOf(addresses))
        selected.value = emptySet()
    }

    /**
     * "Delete all": deletes every held message. When that really empties Blocked, the restored list is cleared with
     * them once the deletion is final (spamui-15), so no sender numbers stay behind.
     */
    fun deleteAll() {
        val ids = idsOf(state.value.groups.map { it.address }.toSet())
        hideAndDelete(ids, clearsRestoredLog = ids.size >= state.value.heldCount)
        selected.value = emptySet()
    }

    /** Delete of single messages from the sender thread, with the same Undo as [delete]. */
    fun deleteMessages(messageIds: Set<Long>) {
        val pending = pendingIds()
        val held = state.value.groups.flatMap { g -> g.messages.map { it.id } }.toSet()
        hideAndDelete(messageIds.filter { it in held && it !in pending })
    }

    private fun hideAndDelete(ids: List<Long>, clearsRestoredLog: Boolean = false) {
        if (ids.isEmpty()) return
        val token = nextToken++
        if (clearsRestoredLog) clearLogTokens += token
        pendingDeletes.update { it + (token to ids) }
        events.trySend(BlockedEvent.Deleted(token, ids.size))
        // The screen's snackbar normally commits; this makes the delete final even when the screen is left
        // with the model still alive (commitDelete ignores a token that is gone or already being committed).
        viewModelScope.launch {
            delay(UNDO_WINDOW_MS)
            commitDelete(token)
        }
    }

    fun undoDelete(token: Long) {
        clearLogTokens -= token
        pendingDeletes.update { it - token }
    }

    fun commitDelete(token: Long) {
        val ids = pendingDeletes.value[token] ?: return
        if (!deleting.add(token)) return
        viewModelScope.launch {
            runCatching { repository.deleteBlocked(ids) }
                .onSuccess { if (clearLogTokens.remove(token)) restoredLog.clear() }
                .onFailure { events.trySend(BlockedEvent.Failed) }
            pendingDeletes.update { it - token }
            deleting -= token
        }
    }

    /** Delete tokens whose commit is running, so the timer and the snackbar never delete twice. */
    private val deleting = HashSet<Long>()

    /**
     * Shows the Undo snackbar again for every action still waiting on one. Call when the screen starts collecting
     * [eventFlow]: after a rotation the old snackbar was cancelled without an answer, which left the rows hidden with
     * no way to undo or finish the action. Events still queued for the old screen are folded in, so none shows twice.
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
        // one being committed or undone finishes on its own).
        cleared = true
        val leftovers = pendingDeletes.value.values.flatten()
        if (clearLogTokens.any { it in pendingDeletes.value }) restoredLog.clear()
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

    /** Ids hidden while a restore or a delete waits for its undo window, or moved by any screen right now. */
    private fun pendingIds(): Set<Long> =
        (pendingRestores.value.values.flatMap { it.claimed } + pendingDeletes.value.values.flatten() +
            moves.state.value.inFlight.keys).toSet()

    private fun idsOf(addresses: Set<String>): List<Long> =
        state.value.groups.filter { g -> addresses.any(g::hasAddress) }.flatMap { g -> g.messages.map { it.id } }

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
