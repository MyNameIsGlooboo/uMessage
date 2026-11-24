@file:Suppress("TooManyFunctions") // The Spam tab's model: one small function per action of the tab, and its Undo.

package com.glooboo.umessage.ui.spam

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glooboo.umessage.R
import com.glooboo.umessage.databases.MessagesDatabase
import com.glooboo.umessage.extensions.ChatMark
import com.glooboo.umessage.extensions.DeleteResult
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.extensions.getAllDrafts
import com.glooboo.umessage.helpers.DefaultSmsRole
import com.glooboo.umessage.helpers.HiddenChats
import com.glooboo.umessage.helpers.refreshConversations
import com.glooboo.umessage.models.Events
import com.glooboo.umessage.spam.BlockReceipt
import com.glooboo.umessage.spam.ChatSorter
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRepository
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.SpamSorting
import com.glooboo.umessage.spam.data.BlockedNotifier
import com.glooboo.umessage.spam.data.SpamProviderMigration
import com.glooboo.umessage.spam.engine.SenderVerdict
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.ui.components.ruleValueText
import com.glooboo.umessage.ui.components.localizeDigits
import com.glooboo.umessage.ui.components.localizedNumber
import com.glooboo.umessage.ui.components.uiLocale
import com.glooboo.umessage.ui.conversations.ConversationRow
import com.glooboo.umessage.ui.conversations.toFolderRow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

/** The Spam tab (4.2) as the screen draws it. */
data class SpamChatsUiState(
    val rows: List<ConversationRow> = emptyList(),
    val causes: Map<Long, SpamCause> = emptyMap(),
    val loading: Boolean = true,
    /** The spam filter switch; off shows the strip "The spam filter is off" with "Turn on". */
    val filterOn: Boolean = true,
    /** U20: held texts the message migration has not moved yet, while it waits; 0 shows no entry. */
    val olderSpam: Int = 0,
    /** 4.4: the sender names asked about before "Not spam" acts; null shows no question. */
    val nameConfirm: NameConfirm? = null,
    /** Why Delete is off now (not the default SMS app), null while it works; read on each load, never in a draw. */
    val deleteOff: Int? = null,
) {
    fun selection(ids: Set<Long>): SpamSelection = SpamChatsPolicy.selection(
        ids.mapNotNull { causes[it] },
        anyUnread = rows.any { it.unread && it.threadId in ids },
    )
}

/** "Not spam" on these chats waits for the answer about their sender [names]. */
data class NameConfirm(val ids: Set<Long>, val names: List<String>)

/** One-shot results the Spam tab shows as snackbars. Each [Windowed.token] ties the Undo to its action. */
sealed interface SpamChatsEvent {
    /** An event with an Undo window: it is closed when its snackbar ends or goes away unanswered (C-1, C-2). */
    sealed interface Windowed : SpamChatsEvent {
        val token: Long
    }

    /** "Not spam" moved [count] chats to Messages; [name] is the one sender's name or number when there was one. */
    data class MovedToMessages(
        override val token: Long,
        val count: Int,
        val name: String?,
        /** L3-4: some of the chats are archived, so they go to Archived, not Messages. */
        val archived: Boolean = false,
    ) : Windowed

    /** "Unblock" moved [count] chats back to Messages. */
    data class Unblocked(override val token: Long, val count: Int, val archived: Boolean = false) : Windowed

    /** "Unblock" removed [count] senders' rules, but where their chats go could not be read: a neutral text. */
    data class UnblockedUnsure(override val token: Long, val count: Int) : Windowed

    /** "Unblock" worked, but the senders are unknown, so their chats stay in Spam; the action is "Not spam". */
    data class StillUnknown(override val token: Long, val ids: Set<Long>) : Windowed

    /** Unblocked in uMessage, but Android's own block list still has the number (it stays in Spam). */
    data class StillOnSystemList(override val token: Long) : Windowed

    data class Blocked(override val token: Long, val count: Int) : Windowed
    data class Deleted(override val token: Long, val count: Int) : Windowed
    data class MarkedAllRead(val count: Int) : SpamChatsEvent

    /** J24: [count] chats got a new text while their delete waited; the old texts went and the new ones were kept. */
    data class KeptNewText(val count: Int) : SpamChatsEvent

    /** J24: [count] chats were not deleted (they left Spam, or the store could not be read) and show again. */
    data class KeptOther(val count: Int) : SpamChatsEvent

    /**
     * A plain message ([arg] fills its one placeholder): why something did not happen (S14, C-4). [viewRule]: the
     * snackbar offers "View rule", which opens the rules (D-6, an Unblock refused by a prefix rule).
     */
    data class Message(val res: Int, val arg: String? = null, val viewRule: Boolean = false) : SpamChatsEvent
    data object Failed : SpamChatsEvent
}

/** What "Unblock" found for one sender after its rules went (C-4). */
enum class UnblockOutcome { BACK_IN_MESSAGES, STILL_UNKNOWN, ANDROID_LIST, OTHER_RULE, UNSURE }

/** [UnblockOutcome] of a sender's place after "Unblock". Null verdict: it could not be read. */
fun unblockOutcome(verdict: SenderVerdict?): UnblockOutcome = when {
    verdict == null -> UnblockOutcome.UNSURE
    verdict is SenderVerdict.Messages -> UnblockOutcome.BACK_IN_MESSAGES
    (verdict as SenderVerdict.Spam).cause == SpamCause.UNKNOWN -> UnblockOutcome.STILL_UNKNOWN
    verdict.ruleId == null -> UnblockOutcome.ANDROID_LIST
    else -> UnblockOutcome.OTHER_RULE
}

/** The rows of the Spam list are built from these; a row is built again only when one of them changed (C-7). */
private data class RowKey(
    val chat: SpamChat,
    val draft: String?,
    val pinned: Boolean,
    val code: String?,
    /** A language change builds the rows again: their words and digits are fixed when a row is built. */
    val language: String,
    /** How old the date label is: the minute while under an hour old, then the day (see [spamTimeBucket]). */
    val timeBucket: Long,
)

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS

/**
 * Changes when the date label of a chat dated [dateSeconds] could read differently: every minute while the chat is
 * under an hour old ("Now", "6 min"), then every local day (a clock time, "Yesterday", a weekday). An old chat's row is
 * not built again every minute.
 */
internal fun spamTimeBucket(dateSeconds: Int, now: Long, zoneOffsetMs: Long): Long {
    val age = now - dateSeconds * 1000L
    return if (age < HOUR_MS) now / MINUTE_MS else -((now + zoneOffsetMs) / DAY_MS) - 1
}

/**
 * Feeds the Spam tab (4.2): the chats with a valid Spam row (S9), each with its cause line, its unread count from both
 * sources (3.4) and, for ten minutes, the "Copy code" button of its newest received text (D1 C, C-14). "Not spam",
 * "Unblock", Block and Delete each offer Undo; Delete asks first and then waits for the Undo window before the texts
 * go from the phone's store (U2, S14).
 *
 * A row is hidden only while an action on it is in flight or its Undo window is open; the window is closed when its
 * snackbar ends, when the snackbar goes away unanswered ([onRouteStarted], the route's cancel handling) or, for
 * deletes, when the app goes to the background ([flushPendingDeletes]). So no chat is ever in neither list (C-1, C-2).
 * Reads are merged into one at a time, rows are cached, and hiding filters the built rows (C-7).
 */
@Suppress("TooManyFunctions", "LargeClass") // One small function per action of the tab, and its Undo.
class SpamChatsViewModel internal constructor(
    application: Application,
    private val repository: () -> SpamRepository,
    private val source: (Context) -> List<SpamChat>,
    private val place: SenderPlace,
    private val isDefault: () -> Boolean,
    private val io: CoroutineDispatcher,
    private val requestSort: (Collection<Long>) -> Unit,
    /** Drops the shared snapshot once after the tab's own write, so [place] reads that write (D-7). */
    private val dropSnapshot: () -> Unit = {},
    private val newestReceived: (Long) -> Pair<String, Int>? = { null },
    /** Deletes each chat up to its mark (J24); the result of each. */
    private val deleter: (Map<Long, ChatMark>, Boolean) -> Map<Long, DeleteResult> = { marks, default ->
        deleteSpamChats(application, marks, default)
    },
    private val markAll: (Collection<SpamChat>) -> Int = { SpamChatsSource.markAllRead(application, it) },
    private val clock: () -> Long = System::currentTimeMillis,
    /** J24: a chat's newest text and place in the store now (see [ChatMark]); blocking, off the main thread. */
    private val marker: (Long) -> ChatMark? = { ChatMark(0L, 0L, inSpam = true) },
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application,
        repository = { SpamRepositoryProvider.get(application) },
        source = SpamChatsSource::load,
        place = SenderPlace.of(application),
        isDefault = { isDefaultSmsApp(application) },
        io = Dispatchers.IO,
        requestSort = { ids -> SpamSorting.requestThreads(application, ids) },
        dropSnapshot = { runCatching { ChatSorter.snapshots(application).invalidate() } },
        newestReceived = { id -> SpamChatsSource.newestReceived(MessagesDatabase.getInstance(application), id) },
        marker = { id -> ChatMark.read(application, id) },
    )

    private val app: Application get() = getApplication()
    private val _state = MutableStateFlow(SpamChatsUiState())
    val state: StateFlow<SpamChatsUiState> = _state.asStateFlow()

    private val _events = Channel<SpamChatsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** The chats last read. Main thread. */
    private var chats: List<SpamChat> = emptyList()

    /** The rows of [chats], hidden ones included; the shown list filters them. Main thread. */
    private var allRows: List<ConversationRow> = emptyList()
    private val rowCache = HashMap<Long, Pair<RowKey, ConversationRow>>()

    // The open Undo windows, by token, and the actions still in flight. Together they are the hidden rows.
    private val inFlight = HashSet<Long>()
    private val notSpams = HashMap<Long, Pair<SenderMoves.NotSpamChange, Set<Long>>>()

    /** Removed rules, every chat asked about, and the chats hidden (those that went back to Messages). */
    private val unblocks = HashMap<Long, Triple<List<SpamRule>, Set<Long>, Set<Long>>>()
    private val blocks = HashMap<Long, List<BlockReceipt>>()

    /** The deletes waiting for their Undo window, by token: each chat with its mark from the moment of the confirm. */
    private val pendingDeletes = LinkedHashMap<Long, Map<Long, ChatMark?>>()

    /** J24: the token of a delete whose window just closed: its Undo must go from the screen (L3-2). */
    private val _deleteClosed = MutableSharedFlow<Long>(extraBufferCapacity = EVENT_BUFFER)
    val deleteClosed: SharedFlow<Long> = _deleteClosed.asSharedFlow()

    /** Chats whose delete runs now: still hidden, no Undo any more, never deleted twice (D-2). */
    private val deleting = HashSet<Long>()
    private var nextToken = 1L

    /**
     * The strip's "Turn on" while the filter is off: it asks first and names the chats that will move (answer 18),
     * as the switch in Settings does.
     */
    val switches = SwitchConfirmer(viewModelScope) { change ->
        if (change is SortChange.Filter) runCatching { repository().updateSettings { it.copy(filterOn = change.on) } }
    }

    /** Asks for a read; reads that arrive while one runs are merged into the next (C-7). */
    private val loads = Channel<Unit>(Channel.CONFLATED)
    private var codeTimer: Job? = null

    init {
        open += this
        EventBus.getDefault().takeUnless { it.isRegistered(this) }?.register(this)
        viewModelScope.launch { for (ignored in loads) readChats() }
        viewModelScope.launch { SpamSorting.changes.drop(1).collect { load() } }
        observeSettings()
    }

    private fun observeSettings() {
        viewModelScope.launch {
            val repo = runCatching { repository() }.getOrNull() ?: return@launch
            // F-2: every held row not verified-done is shown (waiting, given up, stopped, late MMS, restored).
            combine(repo.settings, repo.blocked) { settings, blocked ->
                settings.filterOn to OlderSpam.count(blocked, SpamProviderMigration.keptHere(app, blocked))
            }.flowOn(Dispatchers.IO).catch { }.collect { (filterOn, older) ->
                _state.update { it.copy(filterOn = filterOn, olderSpam = older) }
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onRefresh(@Suppress("unused") event: Events.RefreshConversations) = load()

    /** Reads the Spam list again (the tab shows, Messages was published, the table changed). */
    fun load() {
        loads.trySend(Unit)
    }

    private suspend fun readChats() {
        val fresh = withContext(io) {
            runCatching {
                val now = clock()
                HiddenChats.get(app).withoutHidden(source(app)) { it.threadId }.map { chat ->
                    val received = if (SpamChatsPolicy.mayHoldFreshCode(chat.conversation, now)) {
                        newestReceived(chat.threadId)
                    } else {
                        null
                    }
                    val blocked = chat.cause == SpamCause.BLOCKED
                    val verdict = if (blocked) place.verdict(chat.conversation.phoneNumber) else null
                    val androidListed = unblockOutcome(verdict) == UnblockOutcome.ANDROID_LIST
                    chat.copy(received = received, androidListed = androidListed)
                }
            }.getOrNull()
        } ?: return
        chats = fresh
        val off = withContext(io) { deleteOffNow() }
        _state.update { it.copy(deleteOff = off) }
        rebuildRows()
    }

    /** Counts the builds, so an older build that ends late never replaces a newer one. */
    private var builds = 0

    private suspend fun rebuildRows() {
        val shown = chats
        val build = ++builds
        val built = withContext(Dispatchers.Default) { synchronized(rowCache) { buildRows(shown) } }
        if (build != builds) return
        allRows = built
        publish()
        scheduleCodeExpiry(shown)
    }

    /** Shows [allRows] without the hidden ones: a hide or show filters, it builds nothing (C-7). */
    private fun publish() {
        val hiddenNow = hiddenIds()
        val visible = allRows.filter { it.threadId !in hiddenNow }
        val causes = chats.filter { it.threadId !in hiddenNow }.associate { it.threadId to it.cause }
        _state.update { it.copy(rows = visible, causes = causes, loading = false) }
    }

    private fun hiddenIds(): Set<Long> = HashSet<Long>().apply {
        addAll(inFlight)
        notSpams.values.forEach { addAll(it.second) }
        unblocks.values.forEach { addAll(it.third) }
        pendingDeletes.values.forEach { addAll(it.keys) }
        addAll(deleting)
    }

    private fun buildRows(shown: List<SpamChat>): List<ConversationRow> {
        val drafts = runCatching { app.getAllDrafts() }.getOrDefault(HashMap())
        val pinned = app.config.pinnedConversations
        val locale = app.uiLocale
        val now = clock()
        val language = locale.toLanguageTag()
        val zoneOffset = java.util.TimeZone.getDefault().getOffset(now).toLong()
        val rows = shown.sortedByDescending { it.conversation.date }.map { chat ->
            val key = RowKey(
                chat, drafts[chat.threadId], pinned.contains(chat.threadId.toString()),
                SpamChatsPolicy.code(chat.received, now), language,
                spamTimeBucket(chat.conversation.date, now, zoneOffset),
            )
            rowCache[chat.threadId]?.takeIf { it.first == key }?.second ?: buildRow(chat, key, drafts, pinned, locale)
                .also { rowCache[chat.threadId] = key to it }
        }
        val ids = shown.mapTo(HashSet()) { it.threadId }
        rowCache.keys.retainAll(ids)
        return rows
    }

    /** How many rows were built (not taken from the cache): a hide or show builds none (C-7, D-7). */
    @Volatile internal var rowBuilds = 0
        private set

    private fun buildRow(
        chat: SpamChat,
        key: RowKey,
        drafts: Map<Long, String>,
        pinned: Set<String>,
        locale: java.util.Locale,
    ): ConversationRow {
        val unread = chat.unread
        rowBuilds++
        return chat.conversation.toFolderRow(app, drafts, pinned).copy(
            unread = unread > 0,
            unreadCount = unread,
            unreadBadge = when {
                unread <= 0 -> ""
                unread > MAX_BADGE -> localizeDigits("$MAX_BADGE+", locale)
                else -> localizedNumber(unread, locale)
            },
            causeLine = app.getString(SpamChatsPolicy.causeRes(chat.cause, chat.androidListed)),
            otpCode = key.code,
        )
    }

    /** C-14: a "Copy code" button goes when its ten minutes end, also while nothing else changes. */
    private fun scheduleCodeExpiry(shown: List<SpamChat>) {
        codeTimer?.cancel()
        val now = clock()
        val next = shown.mapNotNull { chat ->
            SpamChatsPolicy.codeEndsAt(chat.received)?.takeIf { SpamChatsPolicy.code(chat.received, now) != null }
        }.minOrNull() ?: return
        codeTimer = viewModelScope.launch {
            delay((next - now).coerceAtLeast(0L) + 1L)
            rebuildRows()
        }
    }

    private fun chosen(ids: Set<Long>): List<SpamChat> = chats.filter { it.threadId in ids }

    private fun send(event: SpamChatsEvent) {
        _events.trySend(event)
    }

    /**
     * The quiet Spam notices of [ids] have done their job: the chats were blocked, unblocked or moved (4.6). Off the
     * main thread: a choice of thousands of chats is one system call each.
     */
    private suspend fun cancelNotices(ids: Collection<Long>) =
        withContext(io) { ids.forEach { BlockedNotifier.cancelChat(app, it) } }

    // ---- Undo windows ------------------------------------------------------------------------------------------

    /**
     * The route started (again): no snackbar of an earlier one is on screen. Every Block, Unblock and "Not spam" window
     * still open is closed and the events queued for it are dropped, so no row stays hidden with no Undo on screen
     * (C-1, C-2; lead decision D-3). A delete waiting for its Undo window is offered again instead (D-1): a rotation,
     * a tab switch or the Spam lock closing never deletes; leaving the app does ([flushPendingDeletes] from onStop).
     * Plain messages still queued are shown.
     */
    fun onRouteStarted() {
        val queued = generateSequence { _events.tryReceive().getOrNull() }.toList()
        queued.filterNot { it is SpamChatsEvent.Windowed }.forEach { _events.trySend(it) }
        notSpams.keys.toList().forEach(::commitNotSpam)
        unblocks.keys.toList().forEach(::commitUnblock)
        blocks.keys.toList().forEach(::commitBlock)
        pendingDeletes.forEach { (token, marks) -> _events.trySend(SpamChatsEvent.Deleted(token, marks.size)) }
    }

    /**
     * Closes the window of [event] without its Undo (its snackbar went away unanswered). A delete keeps its window:
     * the route offers its Undo again when it starts (D-1).
     */
    fun commit(event: SpamChatsEvent.Windowed) {
        when (event) {
            is SpamChatsEvent.MovedToMessages -> commitNotSpam(event.token)
            is SpamChatsEvent.Blocked -> commitBlock(event.token)
            is SpamChatsEvent.Deleted -> Unit
            else -> commitUnblock(event.token)
        }
    }

    // ---- Not spam (unknown senders) ----------------------------------------------------------------------------

    /**
     * "Not spam": a user-made allow rule for each sender, so the chats move to Messages. A sender name asks once first
     * (4.4: anyone can send under a name); a phone number does not. [anyCause]: the chats were unblocked a moment ago
     * and may still be listed as blocked until the sort has run (C-15).
     */
    fun notSpam(ids: Set<Long>, anyCause: Boolean = false) {
        val targets = chosen(ids).filter { anyCause || it.cause == SpamCause.UNKNOWN }
        if (targets.isEmpty()) return
        val names = targets.map { it.conversation.phoneNumber }.filter(SenderBlocking::isSpoofable)
            .distinctBy { it.lowercase() }
        if (names.isNotEmpty()) {
            val ask = NameConfirm(targets.mapTo(HashSet()) { it.threadId }, names)
            _state.update { state -> state.copy(nameConfirm = ask) }
            return
        }
        moveToMessages(targets)
    }

    /** The answer to the name question: allow and move, or leave everything as it was. */
    fun answerNames(allow: Boolean) {
        val ask = _state.value.nameConfirm ?: return
        _state.update { it.copy(nameConfirm = null) }
        if (allow) moveToMessages(chosen(ask.ids))
    }

    private fun moveToMessages(targets: List<SpamChat>) {
        val ids = targets.mapTo(HashSet()) { it.threadId }
        inFlight += ids
        publish()
        viewModelScope.launch {
            val change = runCatching {
                SenderMoves.notSpam(repository(), targets.map { it.conversation.phoneNumber })
            }.getOrNull()
            inFlight -= ids
            if (change == null) {
                publish()
                send(SpamChatsEvent.Failed)
                return@launch
            }
            cancelNotices(ids)
            requestSort(ids)
            val token = nextToken++
            notSpams[token] = change to ids
            publish()
            send(
                SpamChatsEvent.MovedToMessages(
                    token,
                    targets.size,
                    targets.singleOrNull()?.conversation?.title,
                    archived = targets.any { it.conversation.isArchived },
                ),
            )
        }
    }

    fun undoNotSpam(token: Long) {
        val (change, ids) = notSpams[token] ?: return
        viewModelScope.launch {
            runCatching { SenderMoves.undoNotSpam(repository(), change) }
            requestSort(ids)
            notSpams.remove(token)
            publish()
        }
    }

    fun commitNotSpam(token: Long) {
        // The sort has moved them by now; one that did not move shows again instead of vanishing.
        if (notSpams.remove(token) != null) publish()
    }

    // ---- Unblock (blocked senders) -----------------------------------------------------------------------------

    /**
     * "Unblock": the block rules naming each sender go. Back in Messages: Undo. Still unknown: the snackbar says so
     * and offers "Not spam" (4.2). Still blocked by Android's own list, or by a rule that names more than this sender
     * (a prefix): the true reason, and nothing changes. Not sure where it went: a neutral text (C-4). A failure in the
     * middle puts back what was already removed (C-16).
     */
    fun unblock(ids: Set<Long>) {
        val targets = chosen(ids).filter { it.cause == SpamCause.BLOCKED }
        if (targets.isEmpty()) return
        viewModelScope.launch {
            val repo = runCatching { repository() }.getOrNull() ?: return@launch send(SpamChatsEvent.Failed)
            val removed = mutableListOf<SpamRule>()
            val done = runCatching {
                targets.forEach { removed += SenderBlocking.unblock(repo, it.conversation.phoneNumber) }
            }.isSuccess
            if (!done) {
                runCatching { repo.restoreRules(removed) }
                send(SpamChatsEvent.Failed)
                return@launch
            }
            dropSnapshot()
            val verdicts = withContext(io) {
                targets.associate { it.threadId to place.verdict(it.conversation.phoneNumber) }
            }
            reportUnblock(repo, targets, removed, verdicts)
        }
    }

    private suspend fun reportUnblock(
        repo: SpamRepository,
        targets: List<SpamChat>,
        removed: List<SpamRule>,
        verdicts: Map<Long, SenderVerdict?>,
    ) {
        val outcomes = verdicts.mapValues { unblockOutcome(it.value) }
        fun having(outcome: UnblockOutcome) = outcomes.filterValues { it == outcome }.keys
        val all = targets.mapTo(HashSet()) { it.threadId }
        val moved = having(UnblockOutcome.BACK_IN_MESSAGES)
        val unknown = having(UnblockOutcome.STILL_UNKNOWN)
        val unsure = having(UnblockOutcome.UNSURE)
        val blockedByOther = UnblockOutcome.OTHER_RULE in outcomes.values
        val nothingChanges = moved.isEmpty() && unknown.isEmpty() && (removed.isEmpty() || blockedByOther)
        if (nothingChanges) {
            val ruleIds = verdicts.values.mapNotNull { (it as? SenderVerdict.Spam)?.ruleId }
            val otherRule = if (blockedByOther) {
                runCatching { repo.rules.first() }.getOrNull()?.firstOrNull { it.id in ruleIds }
            } else {
                null
            }
            // Nothing moves: what was removed goes back, and the true reason shows.
            runCatching { repo.restoreRules(removed) }
            send(nothingChangedMessage(otherRule, outcomes.values))
            return
        }
        cancelNotices(all)
        requestSort(all)
        val token = nextToken++
        unblocks[token] = Triple(removed, all, moved)
        publish()
        send(
            when {
                moved.isNotEmpty() -> SpamChatsEvent.Unblocked(
                    token,
                    moved.size,
                    archived = targets.any { it.threadId in moved && it.conversation.isArchived },
                )
                unknown.isNotEmpty() -> SpamChatsEvent.StillUnknown(token, unknown)
                unsure.isNotEmpty() -> SpamChatsEvent.UnblockedUnsure(token, targets.size)
                else -> SpamChatsEvent.StillOnSystemList(token)
            },
        )
    }

    private fun nothingChangedMessage(rule: SpamRule?, outcomes: Collection<UnblockOutcome>): SpamChatsEvent = when {
        rule != null && rule.type == RuleType.PREFIX ->
            // A prefix in the language's digits, isolated whole, as the rule row shows it.
            SpamChatsEvent.Message(
                R.string.pt_unblock_refused_prefix,
                ruleValueText(getApplication(), rule.type, rule.pattern),
                viewRule = true,
            )
        UnblockOutcome.ANDROID_LIST in outcomes && UnblockOutcome.OTHER_RULE !in outcomes ->
            SpamChatsEvent.Message(R.string.thread_unblock_done_system)
        else -> SpamChatsEvent.Message(R.string.thread_unblock_failed)
    }

    fun undoUnblock(token: Long) {
        val (removed, ids, _) = unblocks[token] ?: return
        viewModelScope.launch {
            runCatching { repository().restoreRules(removed) }
            requestSort(ids)
            unblocks.remove(token)
            publish()
        }
    }

    fun commitUnblock(token: Long) {
        if (unblocks.remove(token) != null) publish()
    }

    // ---- Block (unknown senders) -------------------------------------------------------------------------------

    /** Block on an unknown sender: a block rule, so the chat stays in Spam as "Blocked by you" (4.2). */
    fun block(ids: Set<Long>) {
        val targets = chosen(ids).filter { it.cause == SpamCause.UNKNOWN }
        if (targets.isEmpty()) return
        viewModelScope.launch {
            val receipts = runCatching {
                SenderMoves.blockSenders(repository(), targets.map { it.conversation.phoneNumber })
            }.getOrNull()
            // No receipt: no sender could be read, nothing was blocked (C-3).
            if (receipts.isNullOrEmpty()) return@launch send(SpamChatsEvent.Failed)
            cancelNotices(targets.map { it.threadId })
            requestSort(targets.map { it.threadId })
            val token = nextToken++
            blocks[token] = receipts
            send(SpamChatsEvent.Blocked(token, receipts.size))
        }
    }

    fun undoBlock(token: Long) {
        val receipts = blocks.remove(token) ?: return
        viewModelScope.launch {
            runCatching { SenderMoves.undoBlocks(repository(), receipts) }
            val addresses = receipts.mapTo(HashSet()) { it.address }
            requestSort(chats.filter { it.conversation.phoneNumber.trim() in addresses }.map { it.threadId })
        }
    }

    fun commitBlock(token: Long) {
        blocks.remove(token)
    }

    // ---- Delete (U2, S14) --------------------------------------------------------------------------------------

    /** Why Delete is off now (not the default SMS app), or null while it works. Asks the system: not in a draw. */
    private fun deleteOffNow(): Int? = if (isDefault()) null else R.string.pt_delete_not_default

    fun canDelete(): Boolean = deleteOffNow() == null

    fun refuseDelete() = send(SpamChatsEvent.Message(R.string.pt_delete_not_default))

    /** The confirm was answered yes: the chats hide, and go for real when the Undo window closes ([commitDelete]). */
    fun delete(ids: Set<Long>) {
        val targets = chosen(ids).mapTo(HashSet()) { it.threadId }
        if (targets.isEmpty()) return
        if (!canDelete()) return refuseDelete()
        // A new delete closes the previous Undo window.
        flushPendingDeletes()
        viewModelScope.launch {
            // J24: the marks are taken before the chats hide and before the Undo is offered, so a text that comes in
            // from now on is never part of this delete.
            val marks = withContext(io) { targets.associateWith { marker(it) } }
            val token = nextToken++
            pendingDeletes[token] = marks
            publish()
            send(SpamChatsEvent.Deleted(token, targets.size))
        }
    }

    fun undoDelete(token: Long) {
        if (pendingDeletes.remove(token) != null) publish()
    }

    /** Whether the delete of [token] still waits for its Undo window. */
    fun isDeletePending(token: Long): Boolean = token in pendingDeletes

    /**
     * The Undo window closed: the texts go from the phone's store. A delete that did not happen says why (S14). The
     * token goes at once, so a second commit (a flush, then the snackbar's own end) does nothing (D-2). J24: only the
     * texts up to the mark of the confirm go. A chat that left Spam or whose mark could not be read is kept, and one
     * that got a newer text keeps it: each is shown again with a note, so the delete never takes a text the user has
     * not seen.
     */
    fun commitDelete(token: Long) {
        val marks = pendingDeletes.remove(token) ?: return
        val ids = marks.keys
        _deleteClosed.tryEmit(token)
        deleting += ids
        viewModelScope.launch(NonCancellable) {
            val report = withContext(io) { deleteMarked(marks, isDefault(), deleter, now = marker) }
            deleting -= ids
            publish()
            if (report.keptNewText > 0) send(SpamChatsEvent.KeptNewText(report.keptNewText))
            if (report.keptOther > 0) send(SpamChatsEvent.KeptOther(report.keptOther))
            report.failure?.let { send(SpamChatsEvent.Message(it)) }
            load()
            refreshConversations()
        }
    }

    /** Commits every delete still waiting for its Undo window (the app goes to the background, C-1). */
    fun flushPendingDeletes() {
        pendingDeletes.keys.toList().forEach(::commitDelete)
    }

    // ---- Read state (3.4) --------------------------------------------------------------------------------------

    fun markRead(ids: Set<Long>) {
        val targets = chosen(ids)
        if (targets.isEmpty()) return
        viewModelScope.launch {
            withContext(io) { SpamChatsSource.markRead(app, targets) }
            load()
            refreshConversations()
        }
    }

    /** "Mark all as read" in the Spam menu (answer 16): one update for uMessage's count, the store where needed. */
    fun markAllRead() {
        val targets = chats.toList()
        viewModelScope.launch {
            val count = withContext(io) { markAll(targets) }
            if (count > 0) send(SpamChatsEvent.MarkedAllRead(count))
            load()
            refreshConversations()
        }
    }

    override fun onCleared() {
        open -= this
        EventBus.getDefault().unregister(this)
        loads.close()
        val leftovers = LinkedHashMap<Long, ChatMark?>()
        pendingDeletes.values.forEach { leftovers += it }
        pendingDeletes.clear()
        if (leftovers.isNotEmpty()) {
            val default = isDefault()
            Thread { runCatching { deleteMarked(leftovers, default, deleter, now = marker) } }.start()
        }
        super.onCleared()
    }

    companion object {
        private const val MAX_BADGE = 99
        private const val EVENT_BUFFER = 16

        /** The models alive now (weakly held): one per screen that showed the Spam tab. */
        private val open: MutableSet<SpamChatsViewModel> =
            java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(java.util.WeakHashMap()))

        /**
         * C-1, D-7: the app goes to the background: every Spam delete still waiting for its Undo window happens now.
         * Makes no model: a tab never opened has nothing waiting. Main thread.
         */
        fun flushOpen() {
            open.toList().forEach { it.flushPendingDeletes() }
        }

        private fun isDefaultSmsApp(context: Context): Boolean =
            runCatching { DefaultSmsRole.status(context) == DefaultSmsRole.Status.Held }.getOrDefault(false)
    }
}
