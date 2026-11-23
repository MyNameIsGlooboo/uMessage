package com.glooboo.umessage.ui.conversations

import com.glooboo.umessage.ui.components.uiLocale
import com.glooboo.umessage.ui.components.localizedNumber
import com.glooboo.umessage.ui.components.localizeDigits
import com.glooboo.umessage.ui.components.formatListTimeStyled
import com.glooboo.umessage.ui.components.displayTitle
import com.glooboo.umessage.ui.components.isBareNumber

import android.app.Application
import android.app.NotificationManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.commons.extensions.copyToClipboard
import org.fossify.commons.extensions.notificationManager
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.markThreadMessagesRead
import com.glooboo.umessage.extensions.markThreadMessagesUnread
import com.glooboo.umessage.extensions.renameConversation
import com.glooboo.umessage.extensions.updateConversationArchivedStatus
import com.glooboo.umessage.helpers.ConversationListSync
import com.glooboo.umessage.helpers.ListTiming
import com.glooboo.umessage.helpers.refreshConversations
import java.util.Collections
import java.util.concurrent.Executors
import com.glooboo.umessage.helpers.HiddenChats
import com.glooboo.umessage.helpers.MessageCategories
import com.glooboo.umessage.helpers.MutePrefs
import com.glooboo.umessage.helpers.OtpCodeExtractor
import com.glooboo.umessage.helpers.W3cPrefs
import com.glooboo.umessage.helpers.messageNotes
import com.glooboo.umessage.helpers.stripControlCharacters
import com.glooboo.umessage.messaging.isShortCodeWithLetters
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.helpers.ConversationRefreshEvents
import com.glooboo.umessage.ui.spam.SimInfo

/**
 * Feeds [ConversationsScreen]. The host activity keeps running the proven telephony sync and hands every
 * resulting list to [submit]; this class owns everything derived from it (ordering, pins, drafts, SIM chips,
 * selection, optimistic edits, undoable delete and archive) and all conversation actions.
 */
class ConversationsViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Application get() = getApplication()

    // Main-thread owned inputs. Every change calls requestRebuild(), which is conflated.
    private var source: List<Conversation> = emptyList()

    /** Drafts and SIM chips (J2: read again only when their source changed). */
    private val extras = DraftsAndSims(app, viewModelScope) { requestRebuild() }

    private var selected: Set<Long> = emptySet()
    private var newBlockedCount = 0

    /** The chats that have private notes; their rows show a note mark. */
    private var notesJob: Job? = null

    @Volatile
    private var noteThreads: Set<Long> = emptySet()

    private var firstRowsLogged = false

    /** R2-1: the [ListTiming.marks] of the last list handed to [submit] by the sync; -1 for none. */
    private var submittedAt = -1

    private val blockedSeenAt = MutableStateFlow(0L)
    private var archiveAvailable = true
    private var hasData = false
    private var importing = false

    /** Chats the user asked to delete and has not confirmed yet; the screen shows a dialog while this is set. */
    private var confirmDeleteIds: List<Long> = emptyList()

    /** Derived rows, reused while nothing a row shows has changed (main-3). Touched only by [build]. */
    private val rowCache = RowCache<Long, RowInput, ConversationRow>()

    /** Thread ids hidden from the list while a delete or archive is pending or in flight. */
    private val hidden = HashSet<Long>()
    private val pendingDeletes = LinkedHashMap<Long, List<Long>>()
    private val archived = HashMap<Long, List<Long>>()

    /** CL-32: the threads an archive marked read because they were unread, by token, so Undo can mark them unread. */
    private val archivedUnread = HashMap<Long, List<Long>>()

    private var nextToken = 1L

    /** Grows each time a list was published; the Spam tab reads its rows again then (a new spam text). */
    private val _published = MutableStateFlow(0L)
    val published: StateFlow<Long> = _published.asStateFlow()

    private val rebuild = Channel<Unit>(Channel.CONFLATED)
    private val _state = MutableStateFlow(ConversationsUiState())
    val state: StateFlow<ConversationsUiState> = _state.asStateFlow()

    private val _events = Channel<ConversationsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val _effects = Channel<ConversationsEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    /** Provider truth (4.1, U5, M-SP3, 3.4): Block and "Report spam" move chats to Spam; the Spam tab's number. */
    private val spamMoves = MessagesSpamMoves(app, viewModelScope, hidden, ::requestRebuild, { _events.trySend(it) }) {
        if (it != newBlockedCount) {
            // J19: store first; a rebuild that runs at once must read the new number.
            newBlockedCount = it
            requestRebuild()
        }
    }

    init {
        viewModelScope.launch {
            for (ignored in rebuild) {
                val snapshot = Snapshot(
                    source, extras.drafts, extras.sims, selected, hidden.toSet(),
                    Status(newBlockedCount, archiveAvailable, hasData, importing, confirmDeleteIds.size),
                )
                val publishedAt = submittedAt
                _state.value = withContext(Dispatchers.Default) { build(snapshot) }
                logFirstRows(publishedAt)
            }
        }
        observeBlockedCount()
        observeNoteThreads()
        extras.start()
    }

    private fun requestRebuild() {
        rebuild.trySend(Unit)
    }

    /** J2: one timing line when the list first has rows to draw (counts and milliseconds only). */
    private fun logFirstRows(publishedAt: Int) {
        val rows = _state.value.rows.size
        ListTiming.rowsBuilt(app, rows, publishedAt)
        if (firstRowsLogged || rows == 0) return
        firstRowsLogged = true
        ListTiming.line(app) { "first rows ready: $rows rows, process up ${ListTiming.sinceProcessStart()} ms" }
    }

    /** The Blocked tab is on screen: everything blocked so far has been seen, so its badge clears. */
    fun markBlockedSeen() {
        val now = System.currentTimeMillis()
        app.config.blockedSeenAt = now
        blockedSeenAt.value = now
        spamMoves.refreshCount(now)
    }

    private fun observeBlockedCount() {
        // Provider truth (3.4): the number is read from the Spam list, after every list publish and every sort.
        spamMoves.observeCount(app.config.blockedSeenAt.also { blockedSeenAt.value = it })
    }

    /** Keeps [noteThreads] up to date; a failing notes table leaves the marks off, it never breaks the list. */
    private fun observeNoteThreads() {
        notesJob = viewModelScope.launch {
            try {
                val notes = app.messageNotes
                withContext(Dispatchers.IO) { runCatching { notes.relink() } }
                notes.observeThreadsWithNotes()
                    .catch { }
                    .collect {
                        noteThreads = it
                        requestRebuild()
                    }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // A missing or closed notes table means no marks, never a crash.
            }
        }
    }

    /**
     * Stops watching the notes and drafts tables: when the model is cleared, and in tests before they close the
     * database.
     */
    internal fun stopObservingNotes() {
        notesJob?.cancel()
        extras.stop()
    }

    // ---- Inputs from the host --------------------------------------------------------------------------

    /**
     * New list from the host. [cached] lists come from the local DB before telephony was read; on the very
     * first run an empty cached list means "still importing", not "no conversations".
     */
    fun submit(conversations: List<Conversation>, cached: Boolean, firstRun: Boolean) {
        source = conversations.toList()
        hasData = true
        importing = cached && firstRun && conversations.isEmpty()
        archiveAvailable = app.config.isArchiveAvailable
        extras.afterPublish()
        requestRebuild()
        _published.value++
        spamMoves.refreshCount(blockedSeenAt.value)
    }

    /** Re-reads everything derived from preferences, drafts and SIMs; called when the screen resumes. */
    fun refresh() {
        extras.refresh()
        requestRebuild()
    }

    // ---- Sync with the phone's message store -----------------------------------------------------------

    private val syncExecutor = Executors.newSingleThreadExecutor()

    /** J2-2: the sync's one quick lane, shut down with [syncExecutor]. */
    private val quickExecutor = Executors.newSingleThreadExecutor { Thread(it, "conversation-list-quick") }

    /** Lives as long as this view model, so a rotated screen keeps it and does not read every thread again. */
    private val sync = ConversationListSync(app, syncExecutor, publish = { conversations, cached ->
        // R2-1: read when the list is handed on, so the rows-built line times only a list published after the mark.
        val publishedAt = ListTiming.marks()
        viewModelScope.launch(Dispatchers.Main) {
            submittedAt = publishedAt
            submit(conversations, cached, firstRun = app.config.appRunCount == 1)
        }
    }).also { it.quickExecutor = quickExecutor }

    /** Refresh events from the rest of the app, heard from [startSync] until the view model is cleared. */
    private val refreshEvents = ConversationRefreshEvents(onRefresh = ::syncChanged)

    /**
     * Shows the saved list, reads the phone's store once and starts listening for refresh events; later calls (a
     * rotated screen) do nothing.
     */
    fun startSync() {
        sync.start()
        refreshEvents.register()
    }

    /**
     * A refresh event: one chat when [threadId] is given (never a full pass), else every thread. J2: while the list is
     * away ([syncLeft]) a full pass waits until it is shown again, and many become one, unless the event is [urgent].
     */
    fun syncChanged(threadId: Long?, urgent: Boolean = false) =
        if (threadId != null) sync.requestThread(threadId) else sync.requestFull(urgent)

    /** The screen came back: chats with newer saved messages move up. */
    fun syncResumed() {
        // The chat marked itself read while this list was away; its rows may have been published before that. J2: they
        // show first, and a full pass asked for while the list was away runs after them.
        sync.requestReturn()
    }

    /** J2: the first resume of a screen (a new one, or one recreated while the list was away). */
    fun syncShown() = sync.requestShown()

    /** J2: the list went out of sight (another screen or app in front, not a rotation). */
    fun syncLeft() = sync.requestAway()

    /** For tests of the screen's wiring: the list counts as out of sight. */
    internal val syncIsAway: Boolean get() = sync.isAway

    /** For tests of the screen's wiring: full passes asked for so far. */
    internal val syncFullRequests: Int get() = sync.fullRequestCount

    // ---- Selection -------------------------------------------------------------------------------------

    val isSelecting: Boolean get() = _state.value.selecting

    fun toggleSelect(threadId: Long) {
        selected = if (threadId in selected) selected - threadId else selected + threadId
        requestRebuild()
    }

    fun startSelect(threadId: Long) {
        if (threadId !in selected) {
            selected = selected + threadId
            requestRebuild()
        }
    }

    fun clearSelection() {
        if (selected.isNotEmpty()) {
            selected = emptySet()
            requestRebuild()
        }
    }

    /** Selects every row, or only [visibleThreadIds] when a filter hides some rows (select all must not reach them). */
    fun selectAll(visibleThreadIds: Collection<Long>? = null) {
        selected = visibleThreadIds?.toHashSet() ?: _state.value.rows.mapTo(HashSet()) { it.threadId }
        requestRebuild()
    }

    private fun selectedConversations(): List<Conversation> = source.filter { it.threadId in selected }

    // ---- Actions ---------------------------------------------------------------------------------------

    fun pinSelected(pin: Boolean) {
        val conversations = selectedConversations()
        if (conversations.isEmpty()) return
        if (pin) {
            app.config.addPinnedConversations(conversations)
        } else {
            app.config.removePinnedConversations(conversations)
        }
        selected = emptySet()
        requestRebuild()
    }

    fun markSelectedRead(read: Boolean) {
        val targets = selectedConversations().filter { it.read != read }
        val targetIds = targets.mapTo(HashSet()) { it.threadId }
        selected = emptySet()
        // Optimistic: the classic list refreshes after the provider round trip; this shows it at once.
        source = source.map { c ->
            if (c.threadId in targetIds) c.copy(read = read, unreadCount = if (read) 0 else c.unreadCount) else c
        }
        requestRebuild()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                targets.forEach {
                    if (read) app.markThreadMessagesRead(it.threadId) else app.markThreadMessagesUnread(it.threadId)
                }
            }
            refreshConversations()
        }
    }

    /** main-21: the threads each "Mark all read" changed, by token, so Undo restores exactly those. */
    private val markedAllRead = HashMap<Long, List<Long>>()

    private val markRunner = MarkReadRunner(viewModelScope, Dispatchers.IO)

    /** How far a running "Mark all read" is; null when none runs. The screen shows a bar while it is set. */
    val markReadProgress: StateFlow<MarkReadProgress?> = markRunner.progress

    /**
     * Marks every unread chat in the list read (chats concealed behind the app lock and chats pending delete or
     * archive are not in the list, so they are left alone). Offers Undo, which makes only these chats unread again.
     */
    fun markAllRead() {
        if (markRunner.isRunning) return
        val concealed = HiddenChats.get(app).hiddenIds()
        val targets = unreadThreadsToMarkRead(source, hidden + concealed)
        if (targets.isEmpty()) return
        val ids = targets.toHashSet()
        val token = nextToken++
        val marked = Collections.synchronizedList(ArrayList<Long>())
        source = source.map { c -> if (c.threadId in ids) c.copy(read = true, unreadCount = 0) else c }
        requestRebuild()
        val mark: (Long) -> Unit = { id ->
            app.markThreadMessagesRead(id)
            marked.add(id)
        }
        markRunner.start(targets, mark) { failed ->
            // A failed run keeps Undo for the chats that did change.
            val done = synchronized(marked) { marked.toList() }
            if (done.isNotEmpty()) markedAllRead[token] = done
            markAllReadEvents(token, done.size, failed).forEach { _events.trySend(it) }
            refreshConversations()
        }
    }

    /** Undo of [markAllRead]: only the chats it changed become unread again. */
    fun undoMarkAllRead(token: Long) {
        val ids = markedAllRead.remove(token) ?: return
        val set = ids.toHashSet()
        source = source.map { c -> if (c.threadId in set) c.copy(read = false) else c }
        requestRebuild()
        viewModelScope.launch(Dispatchers.IO) {
            ids.forEach { runCatching { app.markThreadMessagesUnread(it) } }
            refreshConversations()
        }
    }

    fun archiveSelected() = archive(selected.toList())

    fun archiveOne(threadId: Long) = archive(listOf(threadId))

    private fun archive(threadIds: List<Long>) {
        if (threadIds.isEmpty()) return
        val token = nextToken++
        archived[token] = threadIds
        // CL-32: an archived chat is out of sight, so it must not keep counting as unread.
        val unread = threadsToMarkRead(source, threadIds)
        archivedUnread[token] = unread
        hidden += threadIds
        selected = selected - threadIds.toSet()
        requestRebuild()
        viewModelScope.launch(Dispatchers.IO) {
            val failed = runCatching {
                threadIds.forEach {
                    app.updateConversationArchivedStatus(it, true)
                    app.notificationManager.cancel(it.hashCode())
                }
            }.isFailure
            if (!failed) unread.forEach { runCatching { app.markThreadMessagesRead(it) } }
            withContext(Dispatchers.Main) {
                if (failed) {
                    hidden -= threadIds.toSet()
                    archived.remove(token)
                    archivedUnread.remove(token)
                    requestRebuild()
                    _events.trySend(ConversationsEvent.Failed)
                } else {
                    // Archived for real: drop the rows instead of keeping them hidden, or a later Unarchive (from
                    // the Archived tab, which shares this view model) could never bring them back.
                    source = source.filterNot { it.threadId in threadIds }
                    hidden -= threadIds.toSet()
                    requestRebuild()
                    _events.trySend(ConversationsEvent.Archived(token, threadIds.size))
                }
            }
            refreshConversations()
        }
    }

    /** The Undo window closed without Undo: the saved undo state of the archive is no longer needed. */
    fun commitArchive(token: Long) {
        archived.remove(token)
        archivedUnread.remove(token)
    }

    fun undoArchive(token: Long) {
        val ids = archived.remove(token) ?: return
        val unread = archivedUnread.remove(token).orEmpty()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { ids.forEach { app.updateConversationArchivedStatus(it, false) } }
            unread.forEach { runCatching { app.markThreadMessagesUnread(it) } }
            withContext(Dispatchers.Main) {
                hidden -= ids.toSet()
                requestRebuild()
            }
            refreshConversations()
        }
    }

    /** The Delete button: asks the screen to confirm first; nothing is hidden or deleted until [confirmDelete]. */
    fun deleteSelected() = requestDelete(selected.toList())

    fun deleteOne(threadId: Long) = requestDelete(listOf(threadId))

    private fun requestDelete(threadIds: List<Long>) {
        if (threadIds.isEmpty()) return
        confirmDeleteIds = threadIds
        requestRebuild()
    }

    /** The user said yes in the confirmation dialog. Undo stays available from the snackbar. */
    fun confirmDelete() {
        val ids = confirmDeleteIds
        confirmDeleteIds = emptyList()
        delete(ids)
        requestRebuild()
    }

    fun cancelDelete() {
        if (confirmDeleteIds.isEmpty()) return
        confirmDeleteIds = emptyList()
        requestRebuild()
    }

    /**
     * Hides the conversations and deletes them for real once the undo window closes ([commitDelete]).
     * Like the classic list, whole-thread deletion is permanent (the recycle bin holds single messages, not
     * whole chats, so it cannot make this undoable); the confirmation dialog and the long Undo window guard it.
     */
    private fun delete(threadIds: List<Long>) {
        if (threadIds.isEmpty()) return
        // A new delete closes the previous undo window.
        pendingDeletes.keys.toList().forEach(::commitDelete)
        val token = nextToken++
        pendingDeletes[token] = threadIds
        hidden += threadIds
        selected = selected - threadIds.toSet()
        requestRebuild()
        _events.trySend(ConversationsEvent.Deleted(token, threadIds.size))
    }

    fun undoDelete(token: Long) {
        val ids = pendingDeletes.remove(token) ?: return
        hidden -= ids.toSet()
        requestRebuild()
    }

    fun commitDelete(token: Long) {
        val ids = pendingDeletes.remove(token) ?: return
        viewModelScope.launch(Dispatchers.IO + NonCancellable) {
            // S14 (provider truth): a delete that did not happen says why.
            val (deleted, message) = deleteChats(app, ids)
            withContext(Dispatchers.Main) {
                source = source.filterNot { it.threadId in deleted }
                hidden -= ids.toSet()
                requestRebuild()
                if (message != null) _events.trySend(ConversationsEvent.Message(message))
                else if (deleted.size < ids.size) _events.trySend(ConversationsEvent.Failed)
            }
            refreshConversations()
        }
    }

    /**
     * Shows the Undo snackbar again for every delete still waiting on one. Call when the screen starts collecting
     * [events]: after a rotation the old snackbar was cancelled unanswered, leaving the chat hidden with no Undo.
     * Delete events still queued for the old screen are folded in, so none shows twice.
     */
    fun replayPendingUndo() {
        val queued = generateSequence { _events.tryReceive().getOrNull() }.toList()
        // Provider truth (D-4): a Block whose snackbar never showed is closed now, so its row shows again if it stayed.
        val closed = spamMoves.closeAll()
        queued.filterNot { event ->
            event is ConversationsEvent.Deleted || event.moveToken()?.let { it in closed } == true
        }.forEach { _events.trySend(it) }
        pendingDeletes.forEach { (token, ids) -> _events.trySend(ConversationsEvent.Deleted(token, ids.size)) }
    }

    /** Finalises every open undo window; call when the screen goes to the background. */
    fun flushPendingDeletes() {
        pendingDeletes.keys.toList().forEach(::commitDelete)
    }

    /**
     * Adds a uMessage block rule for each selected sender (CM-06) and moves their chats to Spam (provider truth, 4.1,
     * [MessagesSpamMoves]). The phone's own block list is not touched, because Android discards texts from numbers on
     * it before the app sees them. MC-23: the snackbar offers Undo (through [undoReportSpam]).
     */
    fun blockSelected() {
        // A group chat has no single sender, so it is left out, as blockOne and reportSpamSelected do.
        val conversations = selectedConversations().filterNot { it.isGroupConversation }
        if (conversations.isEmpty()) return
        selected = emptySet()
        requestRebuild()
        block(conversations)
    }

    /**
     * CL-12: the left swipe on a row. Blocks the one sender like [blockSelected] does, with the same Undo; a group chat
     * (no single sender) is left alone.
     */
    fun blockOne(threadId: Long) {
        val conversations = source.filter { it.threadId == threadId && !it.isGroupConversation }
        if (conversations.isEmpty()) return
        block(conversations)
    }

    private fun block(conversations: List<Conversation>) {
        spamMoves.block(conversations, nextToken++, report = false)
    }

    /**
     * "Report spam" (MC-37): blocks each selected sender as "Block number" does, so the chats move to Spam. One Undo
     * takes it back. Group chats are skipped (the action is only offered when none is selected).
     */
    fun reportSpamSelected() {
        val conversations = selectedConversations().filterNot { it.isGroupConversation }
        if (conversations.isEmpty()) return
        // Provider truth: "Report spam" equals Block; it no longer archives (4.1). C-16: the selection clears.
        clearSelection()
        spamMoves.block(conversations, nextToken++, report = true)
    }

    fun undoReportSpam(token: Long) {
        spamMoves.undo(token)
    }

    /** The undo window closed. */
    fun commitReportSpam(token: Long) {
        spamMoves.commit(token)
    }

    fun copyNumber() {
        val conversation = selectedConversations().singleOrNull() ?: return
        app.copyToClipboard(conversation.phoneNumber)
        clearSelection()
    }

    fun dial() {
        val conversation = selectedConversations().singleOrNull() ?: return
        _effects.trySend(ConversationsEffect.Dial(conversation.phoneNumber))
        clearSelection()
    }

    fun addToContact() {
        val conversation = selectedConversations().singleOrNull() ?: return
        _effects.trySend(ConversationsEffect.AddToContact(conversation.phoneNumber))
        clearSelection()
    }

    fun showDetails() {
        val conversation = selectedConversations().singleOrNull() ?: return
        _effects.trySend(ConversationsEffect.Details(conversation.threadId))
        clearSelection()
    }

    fun rename(newTitle: String) {
        val conversation = selectedConversations().singleOrNull() ?: return
        val title = newTitle.trim()
        if (title.isEmpty()) return
        clearSelection()
        viewModelScope.launch(Dispatchers.IO) {
            val updated = runCatching { app.renameConversation(conversation, title) }.getOrNull()
            withContext(Dispatchers.Main) {
                if (updated != null) {
                    source = source.map { if (it.threadId == updated.threadId) updated else it }
                    requestRebuild()
                } else {
                    _events.trySend(ConversationsEvent.Failed)
                }
            }
        }
    }

    override fun onCleared() {
        stopObservingNotes()
        refreshEvents.unregister()
        syncExecutor.shutdown()
        quickExecutor.shutdown()
        // Finish deletions whose undo window was still open; the scope is gone, so run them detached.
        val leftovers = pendingDeletes.values.flatten()
        pendingDeletes.clear()
        if (leftovers.isNotEmpty()) {
            val context = app
            Thread {
                runCatching { deleteChats(context, leftovers) }
                refreshConversations()
            }.start()
        }
        super.onCleared()
    }

    // ---- Derivation ------------------------------------------------------------------------------------

    private class Snapshot(
        val source: List<Conversation>,
        val drafts: Map<Long, String>,
        val sims: List<SimInfo>,
        val selected: Set<Long>,
        val hidden: Set<Long>,
        val status: Status,
    )

    /** The flags and counts of a [Snapshot] that are not lists. */
    private class Status(
        val newBlockedCount: Int,
        val archiveAvailable: Boolean,
        val hasData: Boolean,
        val importing: Boolean,
        val confirmDeleteCount: Int,
    )

    /** A group, a renamed thread, or a title that is a name rather than the number: someone the user knows. */
    private fun isContact(c: Conversation): Boolean =
        c.isGroupConversation || c.usesCustomTitle ||
            (!isBareNumber(c.title) && !c.title.trim().equals(c.phoneNumber.trim(), ignoreCase = true))

    private fun build(s: Snapshot): ConversationsUiState {
        val config = app.config
        val pinned = config.pinnedConversations
        val customNotifications = config.customNotifications
        // One binder call per rebuild instead of one per chat with its own notification channel.
        val mutedChannels = if (customNotifications.isEmpty()) emptySet() else mutedChannelIds(app.notificationManager)
        val locale = app.uiLocale
        val mutePrefs = MutePrefs.get(app)
        // B06: chats hidden behind the app lock stay out of the list until they are unlocked.
        val hiddenChats = HiddenChats.get(app)
        val concealed = hiddenChats.hiddenIds()
        val showSim = s.sims.size >= 2
        // With two SIMs every chat is badged with the SIM of its newest message, as in Spam and Archived.
        val simNames = if (showSim) s.sims.associate { it.slot to SimBadge.displayName(app, it) } else emptyMap()
        val noTextLabel = app.getString(R.string.attachment)
        val now = System.currentTimeMillis()

        // I36: pinned stays above unread, as the user pinned it on purpose.
        val unreadFirst = W3cPrefs.get(app).unreadFirst
        val sorted = s.source
            .filter { it.threadId !in s.hidden && it.threadId !in concealed }
            .sortedWith(
                compareByDescending<Conversation> { pinned.contains(it.threadId.toString()) }
                    .thenByDescending { unreadFirst && !it.read }
                    .thenByDescending { it.date },
            )
        val rows = sorted.map { c ->
            val draft = s.drafts[c.threadId]
            val simSlot = if (showSim && !c.isGroupConversation) {
                val subscription = extras.threadSims[c.threadId] ?: config.getUseSIMIdAtNumber(c.phoneNumber)
                SimBadge.slotToShow(subscription, s.sims)
            } else {
                null
            }
            val input = RowInput(
                threadId = c.threadId, title = c.title, snippet = c.snippet, date = c.date, read = c.read,
                unreadCount = c.unreadCount, photoUri = c.photoUri, isGroup = c.isGroupConversation,
                phoneNumber = c.phoneNumber, scheduled = c.isScheduled, customTitle = c.usesCustomTitle,
                draft = draft.orEmpty(), simSlot = simSlot, simName = simSlot?.let { simNames[it] }.orEmpty(),
                pinned = pinned.contains(c.threadId.toString()),
                muted = mutePrefs.isMuted(c.threadId) ||
                    (customNotifications.contains(c.threadId.toString()) && c.threadId.toString() in mutedChannels),
                otpFresh = !c.read && now - c.date * 1000L in 0..OTP_CHIP_MS,
                hasNotes = c.threadId in noteThreads,
                minute = now / MINUTE_MS, locale = locale,
            )
            rowCache.get(c.threadId, input) {
                val unread = !c.read
                ConversationRow(
                    threadId = c.threadId,
                    // Cached titles from before control characters were stripped may still hold a NUL.
                    // CL-10: the shared display form, also used by Archived, search, the thread header and Details.
                    // N2: each number of a group title too, joined with the language's comma.
                    title = displayTitle(app, c.title.stripControlCharacters()),
                    snippet = conversationPreview(c.snippet, draft, noTextLabel),
                    dateLabel = formatListTimeStyled(app, c.date * 1000L),
                    unread = unread,
                    unreadBadge = when {
                        !unread -> ""
                        c.unreadCount > MAX_BADGE -> localizeDigits("$MAX_BADGE+", app.uiLocale)
                        c.unreadCount <= 0 -> ""
                        else -> localizedNumber(c.unreadCount, app.uiLocale)
                    },
                    unreadCount = c.unreadCount,
                    pinned = input.pinned,
                    muted = input.muted,
                    hasDraft = !draft.isNullOrEmpty(),
                    scheduled = c.isScheduled,
                    simSlot = simSlot,
                    simName = input.simName,
                    photoUri = c.photoUri,
                    isGroup = c.isGroupConversation,
                    phoneNumber = c.phoneNumber,
                    // CL-11: only a fresh, unread message offers its code; the chip goes away after OTP_CHIP_MS.
                    otpCode = if (input.otpFresh) {
                        runCatching { OtpCodeExtractor.extract(c.snippet) }.getOrNull()
                    } else {
                        null
                    },
                    category = MessageCategories.classify(c.phoneNumber, c.snippet, isContact = isContact(c)),
                    isContact = !c.isGroupConversation && isContact(c),
                    hasNotes = input.hasNotes,
                )
            }
        }
        rowCache.retainOnly(sorted.mapTo(HashSet()) { it.threadId })

        val rowIds = rows.mapTo(HashSet()) { it.threadId }
        val selectedIds = s.selected.filterTo(HashSet()) { it in rowIds }
        val chosen = sorted.filter { it.threadId in selectedIds }
        val single = chosen.singleOrNull()
        val actions = if (chosen.isEmpty()) {
            SelectionActions()
        } else {
            SelectionActions(
                count = chosen.size,
                canPin = chosen.any { !pinned.contains(it.threadId.toString()) },
                canUnpin = chosen.all { pinned.contains(it.threadId.toString()) },
                canMarkRead = chosen.any { !it.read },
                canMarkUnread = chosen.any { it.read },
                canArchive = s.status.archiveAvailable,
                canReportSpam = chosen.none { it.isGroupConversation },
                canBlock = chosen.any { !it.isGroupConversation },
                canAddContact = single != null && !single.isGroupConversation,
                canCopyNumber = single != null && !single.isGroupConversation,
                canDial = single != null && !single.isGroupConversation && !isShortCodeWithLetters(single.phoneNumber),
                canRename = single != null && single.isGroupConversation,
                canShowDetails = single != null,
                allSelected = chosen.size == rows.size,
                numbers = chosen.filterNot { it.isGroupConversation }.map { it.phoneNumber }.distinct(),
                renameInitial = single?.title.orEmpty(),
            )
        }

        return ConversationsUiState(
            loading = !s.status.hasData,
            importing = s.status.importing,
            rows = rows,
            selected = selectedIds,
            selection = actions,
            newBlockedCount = s.status.newBlockedCount,
            archiveAvailable = s.status.archiveAvailable,
            hiddenChats = if (hiddenChats.isLockOn()) s.source.count { hiddenChats.isChosen(it.threadId) } else 0,
            hiddenUnlocked = concealed.isEmpty(),
            confirmDeleteCount = s.status.confirmDeleteCount,
        )
    }

    /** Ids of the notification channels switched off, read with one call. */
    private fun mutedChannelIds(manager: NotificationManager): Set<String> = runCatching {
        manager.notificationChannels
            .filter { it.importance == NotificationManager.IMPORTANCE_NONE }
            .mapTo(HashSet()) { it.id }
    }.getOrDefault(emptySet())

    private companion object {
        const val MAX_BADGE = 99
        const val MINUTE_MS = 60_000L

        /** A code chip is shown for this long after the message arrived (ten minutes). */
        const val OTP_CHIP_MS = 10 * 60 * 1000L
    }
}

/**
 * The line under a chat in the list: the draft when there is one, else the newest message's text on one line.
 * DL-08: a newest message without text (a picture message) shows [noTextLabel] instead of a blank line.
 */
internal fun conversationPreview(snippet: String?, draft: String?, noTextLabel: String): String {
    val text = if (!draft.isNullOrEmpty()) draft else snippet.orEmpty()
    return text.replace('\n', ' ').ifBlank { noTextLabel }
}

/** CL-32: of the threads being archived, the ones that are unread now and get marked read by the archive. */
/** main-21: the unread chats "Mark all read" changes: every unread one except those in [excluded]. */
internal fun unreadThreadsToMarkRead(conversations: List<Conversation>, excluded: Set<Long>): List<Long> =
    conversations.filter { !it.read && it.threadId !in excluded }.map { it.threadId }

internal fun threadsToMarkRead(conversations: List<Conversation>, archiving: List<Long>): List<Long> {
    val ids = archiving.toHashSet()
    return conversations.filter { it.threadId in ids && !it.read }.map { it.threadId }
}
