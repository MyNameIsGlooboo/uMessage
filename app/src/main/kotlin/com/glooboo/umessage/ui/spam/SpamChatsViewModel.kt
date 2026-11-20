package com.glooboo.umessage.ui.spam

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.extensions.deleteConversation
import com.glooboo.umessage.extensions.getAllDrafts
import com.glooboo.umessage.helpers.DefaultSmsRole
import com.glooboo.umessage.helpers.HiddenChats
import com.glooboo.umessage.helpers.refreshConversations
import com.glooboo.umessage.extensions.DeleteResult
import com.glooboo.umessage.models.Events
import com.glooboo.umessage.spam.BlockReceipt
import com.glooboo.umessage.spam.FirstRun
import com.glooboo.umessage.spam.SpamRepository
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.SpamSorting
import com.glooboo.umessage.spam.engine.SenderVerdict
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.ui.components.localizeDigits
import com.glooboo.umessage.ui.components.localizedNumber
import com.glooboo.umessage.ui.components.uiLocale
import com.glooboo.umessage.ui.conversations.ConversationRow
import com.glooboo.umessage.ui.conversations.toFolderRow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.commons.extensions.notificationManager
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
) {
    fun selection(ids: Set<Long>): SpamSelection = SpamChatsPolicy.selection(
        ids.mapNotNull { causes[it] },
        anyUnread = rows.any { it.threadId in ids && it.unread },
    )
}

/** "Not spam" on these chats waits for the answer about their sender [names]. */
data class NameConfirm(val ids: Set<Long>, val names: List<String>)

/** One-shot results the Spam tab shows as snackbars. Each [token] ties the Undo to its action. */
sealed interface SpamChatsEvent {
    /** "Not spam" moved [count] chats to Messages; [name] is the one sender's name or number when there was one. */
    data class MovedToMessages(val token: Long, val count: Int, val name: String?) : SpamChatsEvent

    /** "Unblock" moved [count] chats back to Messages. */
    data class Unblocked(val token: Long, val count: Int) : SpamChatsEvent

    /** "Unblock" worked, but the senders are unknown, so their chats stay in Spam; the action is "Not spam". */
    data class StillUnknown(val token: Long, val ids: Set<Long>) : SpamChatsEvent

    /** Unblocked in uMessage, but Android's own block list still has the number (it stays in Spam). */
    data class StillOnSystemList(val token: Long) : SpamChatsEvent

    data class Blocked(val token: Long, val count: Int) : SpamChatsEvent
    data class Deleted(val token: Long, val count: Int) : SpamChatsEvent
    data class MarkedAllRead(val count: Int) : SpamChatsEvent

    /** A plain message: a delete that did not happen (S14), or Delete while uMessage is not the default app. */
    data class Message(val res: Int) : SpamChatsEvent
    data object Failed : SpamChatsEvent
}

/**
 * Feeds the Spam tab (4.2): the chats with a valid Spam row (S9), each with its cause line, its unread count from both
 * sources (3.4) and, for ten minutes, the "Copy code" button (D1 C). "Not spam", "Unblock", Block and Delete each offer
 * Undo; Delete asks first and then waits for the Undo window before the texts go from the phone's store (U2, S14).
 * Every change of a sender asks for that chat to be sorted again ([SpamSorting.requestThreads]); the list follows the
 * Spam table ([SpamSorting.changes]), the app's refresh events and [load] (the tab shows again, Messages published).
 */
@Suppress("TooManyFunctions") // One small function per action of the tab, and its Undo.
class SpamChatsViewModel internal constructor(
    application: Application,
    private val repository: () -> SpamRepository,
    private val source: (Context) -> List<SpamChat>,
    private val place: SenderPlace,
    private val isDefault: () -> Boolean,
    private val io: CoroutineDispatcher,
    private val requestSort: (Collection<Long>) -> Unit,
) : AndroidViewModel(application) {

    constructor(application: Application) : this(
        application,
        repository = { SpamRepositoryProvider.get(application) },
        source = SpamChatsSource::load,
        place = SenderPlace.of(application),
        isDefault = { isDefaultSmsApp(application) },
        io = Dispatchers.IO,
        requestSort = { ids -> SpamSorting.requestThreads(application, ids) },
    )

    private val app: Application get() = getApplication()
    private val _state = MutableStateFlow(SpamChatsUiState())
    val state: StateFlow<SpamChatsUiState> = _state.asStateFlow()

    private val _events = Channel<SpamChatsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** The chats last read, before [hidden] is applied. Main thread. */
    private var chats: List<SpamChat> = emptyList()

    /** Chats hidden while an action on them waits for its Undo window or for the sort. Main thread. */
    private val hidden = HashSet<Long>()
    private var nextToken = 1L
    private val notSpams = HashMap<Long, Pair<SenderMoves.NotSpamChange, Set<Long>>>()
    private val unblocks = HashMap<Long, Pair<List<SpamRule>, Set<Long>>>()
    private val blocks = HashMap<Long, List<BlockReceipt>>()
    private val pendingDeletes = LinkedHashMap<Long, Set<Long>>()

    /**
     * The strip's "Turn on" while the filter is off: it asks first and names the chats that will move (answer 18),
     * as the switch in Settings does.
     */
    val switches = SwitchConfirmer(viewModelScope) { change ->
        if (change is SortChange.Filter) runCatching { repository().updateSettings { it.copy(filterOn = change.on) } }
    }

    /** Loads run one after another, so an older read never overwrites a newer one. */
    private val loader = java.util.concurrent.Executors.newSingleThreadExecutor()

    init {
        EventBus.getDefault().takeUnless { it.isRegistered(this) }?.register(this)
        viewModelScope.launch { SpamSorting.changes.drop(1).collect { load() } }
        observeSettings()
    }

    private fun observeSettings() {
        viewModelScope.launch {
            val repo = runCatching { repository() }.getOrNull() ?: return@launch
            val firstRun = FirstRun.state(app)
            combine(repo.settings, repo.blocked, firstRun) { settings, blocked, run ->
                settings.filterOn to if (run.olderSpamWaiting) SpamChatsPolicy.olderSpam(blocked).size else 0
            }.catch { }.collect { (filterOn, older) ->
                _state.update { it.copy(filterOn = filterOn, olderSpam = older) }
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onRefresh(@Suppress("unused") event: Events.RefreshConversations) = load()

    /** Reads the Spam list again (the tab shows, Messages was published, the table changed). */
    fun load() {
        if (loader.isShutdown) return
        runCatching {
            loader.execute {
                val fresh = runCatching { source(app) }.getOrNull() ?: return@execute
                val visible = HiddenChats.get(app).withoutHidden(fresh) { it.threadId }
                viewModelScope.launch {
                    chats = visible
                    // A chat that left the list (sorted out, deleted) needs no hiding any more.
                    val ids = visible.mapTo(HashSet()) { it.threadId }
                    hidden.retainAll { it in ids || pendingDeletes.values.any { set -> it in set } }
                    publish()
                }
            }
        }
    }

    /** Counts the rows built, so an older build that ends late never replaces a newer one. */
    private var builds = 0

    private fun publish() {
        val shown = chats.filter { it.threadId !in hidden }
        val build = ++builds
        viewModelScope.launch {
            val rows = withContext(Dispatchers.Default) { buildRows(shown) }
            if (build != builds) return@launch
            _state.update { state ->
                state.copy(rows = rows, causes = shown.associate { it.threadId to it.cause }, loading = false)
            }
        }
    }

    private fun buildRows(shown: List<SpamChat>): List<ConversationRow> {
        val drafts = runCatching { app.getAllDrafts() }.getOrDefault(HashMap())
        val pinned = app.config.pinnedConversations
        val locale = app.uiLocale
        val now = System.currentTimeMillis()
        return shown.sortedByDescending { it.conversation.date }.map { chat ->
            val unread = chat.unread
            chat.conversation.toFolderRow(app, drafts, pinned).copy(
                unread = unread > 0,
                unreadCount = unread,
                unreadBadge = when {
                    unread <= 0 -> ""
                    unread > MAX_BADGE -> localizeDigits("$MAX_BADGE+", locale)
                    else -> localizedNumber(unread, locale)
                },
                causeLine = app.getString(SpamChatsPolicy.causeRes(chat.cause)),
                otpCode = SpamChatsPolicy.code(chat.conversation, now),
            )
        }
    }

    private fun chosen(ids: Set<Long>): List<SpamChat> = chats.filter { it.threadId in ids }

    private fun hide(ids: Collection<Long>) {
        hidden += ids
        publish()
    }

    private fun show(ids: Collection<Long>) {
        hidden -= ids.toSet()
        publish()
    }

    // ---- Not spam (unknown senders) ----------------------------------------------------------------------------

    /**
     * "Not spam": a user-made allow rule for each sender, so the chats move to Messages. A sender name asks once first
     * (4.4: anyone can send under a name); a phone number does not.
     */
    fun notSpam(ids: Set<Long>) {
        val targets = chosen(ids).filter { it.cause == SpamCause.UNKNOWN }
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
        hide(ids)
        viewModelScope.launch {
            val change = runCatching {
                SenderMoves.notSpam(repository(), targets.map { it.conversation.phoneNumber })
            }.getOrElse {
                show(ids)
                _events.trySend(SpamChatsEvent.Failed)
                return@launch
            }
            requestSort(ids)
            val token = nextToken++
            notSpams[token] = change to ids
            val name = targets.singleOrNull()?.conversation?.title
            _events.trySend(SpamChatsEvent.MovedToMessages(token, targets.size, name))
        }
    }

    fun undoNotSpam(token: Long) {
        val (change, ids) = notSpams.remove(token) ?: return
        viewModelScope.launch {
            runCatching { SenderMoves.undoNotSpam(repository(), change) }
            requestSort(ids)
            show(ids)
        }
    }

    fun commitNotSpam(token: Long) {
        val (_, ids) = notSpams.remove(token) ?: return
        // The sort has moved them by now; one that did not move shows again instead of vanishing.
        show(ids)
    }

    // ---- Unblock (blocked senders) -----------------------------------------------------------------------------

    /**
     * "Unblock": the block rules naming each sender go. A sender who is then unknown stays in Spam, and the snackbar
     * says so and offers "Not spam" (4.2); the others go back to Messages.
     */
    fun unblock(ids: Set<Long>) {
        val targets = chosen(ids).filter { it.cause == SpamCause.BLOCKED }
        if (targets.isEmpty()) return
        viewModelScope.launch {
            val repo = runCatching { repository() }.getOrNull()
            val removed = repo?.let { r ->
                runCatching { targets.flatMap { SenderBlocking.unblock(r, it.conversation.phoneNumber) } }.getOrNull()
            }
            if (repo == null || removed == null) {
                _events.trySend(SpamChatsEvent.Failed)
                return@launch
            }
            val places = withContext(io) {
                targets.associate { it.threadId to place.verdict(it.conversation.phoneNumber) }
            }
            val moved = places.filterValues { it is SenderVerdict.Messages }.keys
            val unknown = places.filterValues { (it as? SenderVerdict.Spam)?.cause == SpamCause.UNKNOWN }.keys
            val all = targets.mapTo(HashSet()) { it.threadId }
            hide(moved)
            requestSort(all)
            val token = nextToken++
            unblocks[token] = removed to all
            _events.trySend(
                when {
                    moved.isNotEmpty() -> SpamChatsEvent.Unblocked(token, moved.size)
                    unknown.isNotEmpty() -> SpamChatsEvent.StillUnknown(token, unknown)
                    else -> SpamChatsEvent.StillOnSystemList(token)
                },
            )
        }
    }

    fun undoUnblock(token: Long) {
        val (removed, ids) = unblocks.remove(token) ?: return
        viewModelScope.launch {
            runCatching { repository().restoreRules(removed) }
            requestSort(ids)
            show(ids)
        }
    }

    fun commitUnblock(token: Long) {
        val (_, ids) = unblocks.remove(token) ?: return
        show(ids)
    }

    // ---- Block (unknown senders) -------------------------------------------------------------------------------

    /** Block on an unknown sender: a block rule, so the chat stays in Spam as "Blocked by you" (4.2). */
    fun block(ids: Set<Long>) {
        val targets = chosen(ids).filter { it.cause == SpamCause.UNKNOWN }
        if (targets.isEmpty()) return
        viewModelScope.launch {
            val receipts = runCatching {
                SenderMoves.blockSenders(repository(), targets.map { it.conversation.phoneNumber })
            }.getOrElse {
                _events.trySend(SpamChatsEvent.Failed)
                return@launch
            }
            requestSort(targets.map { it.threadId })
            val token = nextToken++
            blocks[token] = receipts
            _events.trySend(SpamChatsEvent.Blocked(token, targets.size))
        }
    }

    fun undoBlock(token: Long) {
        val receipts = blocks.remove(token) ?: return
        viewModelScope.launch {
            runCatching { SenderMoves.undoBlocks(repository(), receipts) }
            requestSort(chats.filter { chat -> receipts.any { it.address == chat.conversation.phoneNumber } }
                .map { it.threadId })
        }
    }

    fun commitBlock(token: Long) {
        blocks.remove(token)
    }

    // ---- Delete (U2, S14) --------------------------------------------------------------------------------------

    /** False while uMessage is not the default SMS app: Delete is off then, and [refuseDelete] says why. */
    fun canDelete(): Boolean = isDefault()

    fun refuseDelete() {
        _events.trySend(SpamChatsEvent.Message(R.string.pt_delete_not_default))
    }

    /** The confirm was answered yes: the chats hide, and go for real when the Undo window closes ([commitDelete]). */
    fun delete(ids: Set<Long>) {
        val targets = chosen(ids).mapTo(HashSet()) { it.threadId }
        if (targets.isEmpty()) return
        if (!isDefault()) {
            refuseDelete()
            return
        }
        // A new delete closes the previous Undo window.
        pendingDeletes.keys.toList().forEach(::commitDelete)
        val token = nextToken++
        pendingDeletes[token] = targets
        hide(targets)
        _events.trySend(SpamChatsEvent.Deleted(token, targets.size))
    }

    fun undoDelete(token: Long) {
        val ids = pendingDeletes.remove(token) ?: return
        show(ids)
    }

    /** The Undo window closed: the texts go from the phone's store. A delete that did not happen says why (S14). */
    fun commitDelete(token: Long) {
        val ids = pendingDeletes.remove(token) ?: return
        viewModelScope.launch(NonCancellable) {
            val result = withContext(io) { deleteNow(app, ids, isDefault()) }
            show(ids)
            result.messageRes?.let { _events.trySend(SpamChatsEvent.Message(it)) }
            load()
            refreshConversations()
        }
    }

    /** Commits every delete still waiting for its Undo window (the tab goes to the background). */
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

    /** "Mark all as read" in the Spam menu (answer 16): every Spam chat's unread goes to 0, in both sources. */
    fun markAllRead() {
        val targets = chats.toList()
        viewModelScope.launch {
            val count = withContext(io) { SpamChatsSource.markRead(app, targets) }
            if (count > 0) _events.trySend(SpamChatsEvent.MarkedAllRead(count))
            load()
            refreshConversations()
        }
    }

    override fun onCleared() {
        EventBus.getDefault().unregister(this)
        loader.shutdown()
        val leftovers = pendingDeletes.values.flatten().toSet()
        pendingDeletes.clear()
        if (leftovers.isNotEmpty()) {
            val context = app
            val default = isDefault()
            Thread { runCatching { deleteNow(context, leftovers, default) } }.start()
        }
        super.onCleared()
    }

    private companion object {
        const val MAX_BADGE = 99

        fun isDefaultSmsApp(context: Context): Boolean =
            runCatching { DefaultSmsRole.status(context) == DefaultSmsRole.Status.Held }.getOrDefault(false)

        /** Deletes [ids] from the phone's store; the first result that is not "deleted" is the one to show. */
        fun deleteNow(context: Context, ids: Set<Long>, isDefault: Boolean): DeleteResult {
            var shown = DeleteResult.DELETED
            ids.forEach { id ->
                val result = runCatching { context.deleteConversation(id, isDefault) }.getOrDefault(DeleteResult.FAILED)
                if (result == DeleteResult.DELETED) {
                    runCatching { context.notificationManager.cancel(id.hashCode()) }
                } else if (shown == DeleteResult.DELETED) {
                    shown = result
                }
            }
            return shown
        }
    }
}
