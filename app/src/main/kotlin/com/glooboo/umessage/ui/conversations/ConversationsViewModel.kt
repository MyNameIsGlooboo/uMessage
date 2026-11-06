package com.glooboo.umessage.ui.conversations

import com.glooboo.umessage.ui.components.uiLocale
import com.glooboo.umessage.ui.components.localizedNumber
import com.glooboo.umessage.ui.components.localizeDigits
import com.glooboo.umessage.ui.components.formatListTimeStyled
import com.glooboo.umessage.ui.components.displayTitle
import com.glooboo.umessage.ui.components.isBareNumber

import android.app.Application
import android.app.NotificationManager
import android.telephony.SmsManager
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.commons.extensions.copyToClipboard
import org.fossify.commons.extensions.notificationManager
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.extensions.deleteConversation
import com.glooboo.umessage.extensions.getAllDrafts
import com.glooboo.umessage.extensions.markThreadMessagesRead
import com.glooboo.umessage.extensions.markThreadMessagesUnread
import com.glooboo.umessage.extensions.renameConversation
import com.glooboo.umessage.extensions.updateConversationArchivedStatus
import com.glooboo.umessage.helpers.ConversationListSync
import com.glooboo.umessage.helpers.refreshConversations
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
import com.glooboo.umessage.ui.spam.SimDirectory
import com.glooboo.umessage.helpers.ConversationRefreshEvents
import com.glooboo.umessage.ui.spam.SenderBlocking
import com.glooboo.umessage.ui.spam.SimInfo
import com.glooboo.umessage.ui.spam.SpamRepositoryProvider

/**
 * Feeds [ConversationsScreen]. The host activity keeps running the proven telephony sync and hands every
 * resulting list to [submit]; this class owns everything derived from it (ordering, pins, drafts, SIM chips,
 * selection, optimistic edits, undoable delete and archive) and all conversation actions.
 */
class ConversationsViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Application get() = getApplication()

    // Main-thread owned inputs. Every change calls requestRebuild(), which is conflated.
    private var source: List<Conversation> = emptyList()
    private var drafts: Map<Long, String> = emptyMap()
    private var sims: List<SimInfo> = emptyList()
    private var selected: Set<Long> = emptySet()
    private var newBlockedCount = 0

    /** The chats that have private notes; their rows show a note mark. */
    private var notesJob: Job? = null

    @Volatile
    private var noteThreads: Set<Long> = emptySet()
    private val blockedSeenAt = MutableStateFlow(0L)
    private var archiveAvailable = true
    private var hasData = false
    private var importing = false

    /** Thread ids hidden from the list while a delete or archive is pending or in flight. */
    private val hidden = HashSet<Long>()
    private val pendingDeletes = LinkedHashMap<Long, List<Long>>()
    private val archived = HashMap<Long, List<Long>>()

    /** CL-32: the threads an archive marked read because they were unread, by token, so Undo can mark them unread. */
    private val archivedUnread = HashMap<Long, List<Long>>()

    /** "Report spam" actions Undo can still take back: the rules added and the threads archived, by token. */
    private val reports = HashMap<Long, Pair<SenderBlocking.Change, List<Long>>>()
    private var nextToken = 1L

    private val rebuild = Channel<Unit>(Channel.CONFLATED)
    private val _state = MutableStateFlow(ConversationsUiState())
    val state: StateFlow<ConversationsUiState> = _state.asStateFlow()

    private val _events = Channel<ConversationsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val _effects = Channel<ConversationsEffect>(Channel.BUFFERED)
    val effects = _effects.receiveAsFlow()

    init {
        viewModelScope.launch {
            for (ignored in rebuild) {
                val snapshot = Snapshot(
                    source, drafts, sims, selected, hidden.toSet(), newBlockedCount,
                    archiveAvailable, hasData, importing,
                )
                _state.value = withContext(Dispatchers.Default) { build(snapshot) }
            }
        }
        observeBlockedCount()
        observeNoteThreads()
        refreshSims()
        refreshDrafts()
    }

    private fun requestRebuild() {
        rebuild.trySend(Unit)
    }

    /** The Blocked tab is on screen: everything blocked so far has been seen, so its badge clears. */
    fun markBlockedSeen() {
        val now = System.currentTimeMillis()
        app.config.blockedSeenAt = now
        blockedSeenAt.value = now
    }

    private fun observeBlockedCount() {
        viewModelScope.launch {
            val repository = runCatching { SpamRepositoryProvider.get(app) }.getOrNull() ?: return@launch
            blockedSeenAt.value = app.config.blockedSeenAt
            combine(repository.blocked, blockedSeenAt) { blocked, seenAt -> blocked.count { it.receivedAt > seenAt } }
                .catch { }
                .collect {
                    newBlockedCount = it
                    requestRebuild()
                }
        }
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

    /** Stops watching the notes table: when the model is cleared, and in tests before they close the database. */
    internal fun stopObservingNotes() {
        notesJob?.cancel()
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
        refreshSims()
        requestRebuild()
    }

    /** Re-reads everything derived from preferences, drafts and SIMs; called when the screen resumes. */
    fun refresh() {
        refreshDrafts()
        refreshSims()
        requestRebuild()
    }

    /** Reloads drafts; called on resume because the thread screen edits them. */
    fun refreshDrafts() {
        viewModelScope.launch {
            val fresh = withContext(Dispatchers.IO) {
                runCatching { app.getAllDrafts().toMap() }.getOrDefault(emptyMap())
            }
            if (fresh != drafts) {
                drafts = fresh
                requestRebuild()
            }
        }
    }

    /** SIM of each thread's newest message (sent or received), so chips show for every conversation. */
    @Volatile private var threadSims: Map<Long, Int> = emptyMap()

    /** MC-18: the thread SIMs are read only with two SIMs, and then only messages newer than the last read. */
    private fun refreshSims() {
        viewModelScope.launch {
            val (freshSims, freshThreadSims) = withContext(Dispatchers.IO) {
                val loaded = runCatching { SimDirectory.load(app) }.getOrDefault(emptyList())
                loaded to runCatching { ThreadSimCache.get(app).load(app, loaded.size) }.getOrDefault(emptyMap())
            }
            if (freshSims != sims || freshThreadSims != threadSims) {
                sims = freshSims
                threadSims = freshThreadSims
                requestRebuild()
            }
        }
    }

    // ---- Sync with the phone's message store -----------------------------------------------------------

    private val syncExecutor = Executors.newSingleThreadExecutor()

    /** Lives as long as this view model, so a rotated screen keeps it and does not read every thread again. */
    private val sync = ConversationListSync(app, syncExecutor, publish = { conversations, cached ->
        viewModelScope.launch(Dispatchers.Main) {
            submit(conversations, cached, firstRun = app.config.appRunCount == 1)
        }
    })

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

    /** A refresh event: one chat when [threadId] is given, else every thread. */
    fun syncChanged(threadId: Long?) = if (threadId != null) sync.requestThread(threadId) else sync.requestFull()

    /** The screen came back: chats with newer saved messages move up. */
    fun syncResumed() {
        sync.requestBump()
        // The chat marked itself read while this list was away; its rows may have been published before that.
        sync.requestRepublish()
    }

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

    fun selectAll() {
        selected = _state.value.rows.mapTo(HashSet()) { it.threadId }
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

    fun deleteSelected() = delete(selected.toList())

    fun deleteOne(threadId: Long) = delete(listOf(threadId))

    /**
     * Hides the conversations and deletes them for real once the undo window closes ([commitDelete]).
     * Like the classic list, whole-thread deletion is permanent; the recycle bin setting only governs
     * deleting single messages inside a thread.
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
            runCatching {
                ids.forEach {
                    app.deleteConversation(it)
                    app.notificationManager.cancel(it.hashCode())
                }
            }
            withContext(Dispatchers.Main) {
                source = source.filterNot { it.threadId in ids }
                hidden -= ids.toSet()
                requestRebuild()
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
        queued.filterNot { it is ConversationsEvent.Deleted }.forEach { _events.trySend(it) }
        pendingDeletes.forEach { (token, ids) -> _events.trySend(ConversationsEvent.Deleted(token, ids.size)) }
    }

    /** Finalises every open undo window; call when the screen goes to the background. */
    fun flushPendingDeletes() {
        pendingDeletes.keys.toList().forEach(::commitDelete)
    }

    /**
     * Adds a uMessage block rule for each selected sender (CM-06): new texts from them go to Blocked messages, where
     * they can be read and restored. The phone's own block list is not touched, because Android discards texts from
     * numbers on it before the app sees them. The conversations stay in the list with their old messages. MC-23: the
     * snackbar offers Undo, which removes the rules again (through [undoReportSpam], with nothing to unarchive).
     */
    fun blockSelected() {
        val conversations = selectedConversations()
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
        val numbers = conversations.map { it.phoneNumber }
        viewModelScope.launch {
            runCatching { SenderBlocking.block(SpamRepositoryProvider.get(app), numbers) }
                .onSuccess { change ->
                    val token = nextToken++
                    reports[token] = change to emptyList()
                    _events.trySend(ConversationsEvent.Blocked(token, conversations.size))
                }
                .onFailure { _events.trySend(ConversationsEvent.Failed) }
        }
    }

    /**
     * "Report spam" (MC-37): blocks each selected sender with a uMessage rule, as "Block number" does, so their new
     * texts go to Blocked messages, and archives the chats so the spam leaves the list. The old messages stay in the
     * archived chat; nothing is deleted. One Undo removes the rules and unarchives. Group chats are skipped (the
     * action is only offered when none is selected). When archiving is not available the chats stay in the list.
     */
    fun reportSpamSelected() {
        val conversations = selectedConversations().filterNot { it.isGroupConversation }
        if (conversations.isEmpty()) return
        val threadIds = conversations.map { it.threadId }
        val numbers = conversations.map { it.phoneNumber }
        val archive = archiveAvailable
        if (archive) hidden += threadIds
        selected = selected - threadIds.toSet()
        requestRebuild()
        viewModelScope.launch {
            val change = runCatching { SenderBlocking.block(SpamRepositoryProvider.get(app), numbers) }.getOrElse {
                hidden -= threadIds.toSet()
                requestRebuild()
                _events.trySend(ConversationsEvent.Failed)
                return@launch
            }
            // A failed archive keeps the block: the report still did its main job, and the snackbar says so.
            val archivedOk = archive && withContext(Dispatchers.IO) {
                runCatching {
                    threadIds.forEach {
                        app.updateConversationArchivedStatus(it, true)
                        app.notificationManager.cancel(it.hashCode())
                    }
                }.isSuccess
            }
            if (archivedOk) source = source.filterNot { it.threadId in threadIds }
            hidden -= threadIds.toSet()
            requestRebuild()
            val token = nextToken++
            reports[token] = change to (if (archivedOk) threadIds else emptyList())
            _events.trySend(ConversationsEvent.ReportedSpam(token, conversations.size, archivedOk))
            if (archivedOk) withContext(Dispatchers.IO) { refreshConversations() }
        }
    }

    fun undoReportSpam(token: Long) {
        val (change, threadIds) = reports.remove(token) ?: return
        viewModelScope.launch {
            runCatching { SenderBlocking.undo(SpamRepositoryProvider.get(app), change) }
            if (threadIds.isNotEmpty()) {
                withContext(Dispatchers.IO) {
                    runCatching { threadIds.forEach { app.updateConversationArchivedStatus(it, false) } }
                    refreshConversations()
                }
            }
        }
    }

    /** The undo window closed. */
    fun commitReportSpam(token: Long) {
        reports.remove(token)
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
        // Finish deletions whose undo window was still open; the scope is gone, so run them detached.
        val leftovers = pendingDeletes.values.flatten()
        pendingDeletes.clear()
        if (leftovers.isNotEmpty()) {
            val context = app
            Thread {
                runCatching {
                    leftovers.forEach {
                        context.deleteConversation(it)
                        context.notificationManager.cancel(it.hashCode())
                    }
                }
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
        val newBlockedCount: Int,
        val archiveAvailable: Boolean,
        val hasData: Boolean,
        val importing: Boolean,
    )

    /** A group, a renamed thread, or a title that is a name rather than the number: someone the user knows. */
    private fun isContact(c: Conversation): Boolean =
        c.isGroupConversation || c.usesCustomTitle ||
            (!isBareNumber(c.title) && !c.title.trim().equals(c.phoneNumber.trim(), ignoreCase = true))

    private fun build(s: Snapshot): ConversationsUiState {
        val config = app.config
        val withNotes = noteThreads
        val pinned = config.pinnedConversations
        val manager = app.notificationManager
        val customNotifications = config.customNotifications
        val mutePrefs = MutePrefs.get(app)
        // B06: chats hidden behind the app lock stay out of the list until they are unlocked.
        val hiddenChats = HiddenChats.get(app)
        val concealed = hiddenChats.hiddenIds()
        val showSim = s.sims.size >= 2
        // Only a chat whose newest message used the other SIM is badged; the default SIM is the quiet case.
        val systemSim = if (showSim) runCatching { SmsManager.getDefaultSmsSubscriptionId() }.getOrDefault(-1) else -1
        val defaultSim = SimBadge.defaultSubscription(config.defaultSimId, systemSim)
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
            val hasDraft = !draft.isNullOrEmpty()
            val unread = !c.read
            val simSlot = if (showSim && !c.isGroupConversation) {
                val subscription = threadSims[c.threadId] ?: config.getUseSIMIdAtNumber(c.phoneNumber)
                SimBadge.slotToShow(subscription, s.sims, defaultSim)
            } else {
                null
            }
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
                pinned = pinned.contains(c.threadId.toString()),
                muted = mutePrefs.isMuted(c.threadId) ||
                    (customNotifications.contains(c.threadId.toString()) && isMuted(manager, c.threadId)),
                hasDraft = hasDraft,
                scheduled = c.isScheduled,
                simSlot = simSlot,
                simName = simSlot?.let { simNames[it] }.orEmpty(),
                photoUri = c.photoUri,
                isGroup = c.isGroupConversation,
                phoneNumber = c.phoneNumber,
                // CL-11: only a fresh, unread message offers its code; the chip goes away after OTP_CHIP_MS.
                otpCode = if (unread && now - c.date * 1000L in 0..OTP_CHIP_MS) {
                    runCatching { OtpCodeExtractor.extract(c.snippet) }.getOrNull()
                } else {
                    null
                },
                category = MessageCategories.classify(c.phoneNumber, c.snippet, isContact = isContact(c)),
                hasNotes = c.threadId in withNotes,
            )
        }

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
                canArchive = s.archiveAvailable,
                canReportSpam = chosen.none { it.isGroupConversation },
                canAddContact = single != null && !single.isGroupConversation,
                canCopyNumber = single != null && !single.isGroupConversation,
                canDial = single != null && !single.isGroupConversation && !isShortCodeWithLetters(single.phoneNumber),
                canRename = single != null && single.isGroupConversation,
                canShowDetails = single != null,
                allSelected = chosen.size == rows.size,
                numbers = chosen.map { it.phoneNumber }.distinct(),
                renameInitial = single?.title.orEmpty(),
            )
        }

        return ConversationsUiState(
            loading = !s.hasData,
            importing = s.importing,
            rows = rows,
            selected = selectedIds,
            selection = actions,
            newBlockedCount = s.newBlockedCount,
            archiveAvailable = s.archiveAvailable,
            hiddenChats = if (hiddenChats.isLockOn()) s.source.count { hiddenChats.isChosen(it.threadId) } else 0,
            hiddenUnlocked = concealed.isEmpty(),
        )
    }

    private fun isMuted(manager: NotificationManager, threadId: Long): Boolean = runCatching {
        manager.getNotificationChannel(threadId.toString())?.importance == NotificationManager.IMPORTANCE_NONE
    }.getOrDefault(false)

    private companion object {
        const val MAX_BADGE = 99

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
internal fun threadsToMarkRead(conversations: List<Conversation>, archiving: List<Long>): List<Long> {
    val ids = archiving.toHashSet()
    return conversations.filter { it.threadId in ids && !it.read }.map { it.threadId }
}
