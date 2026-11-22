package com.glooboo.umessage.ui.main

import android.app.Application
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.helpers.AppReturnWatch
import com.glooboo.umessage.helpers.HiddenChats
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.helpers.SpamReaders
import com.glooboo.umessage.helpers.messageNotes
import com.glooboo.umessage.interfaces.SearchPlace
import com.glooboo.umessage.models.SearchResult
import com.glooboo.umessage.ui.components.displayNumber
import com.glooboo.umessage.ui.components.displayTitle
import com.glooboo.umessage.ui.lock.LockScope
import com.glooboo.umessage.ui.spam.BlockedFilters
import com.glooboo.umessage.ui.spam.SenderKeys
import com.glooboo.umessage.ui.spam.SpamRepositoryProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.fossify.commons.extensions.appLockManager
import com.glooboo.umessage.ui.components.formatListTimeStyled
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The main screen's search: the field's state and the debounced search behind it. Rotation keeps the view model, so an
 * open search keeps its query and results; after the process is killed the query comes back and runs again.
 */
class MainSearchViewModel(application: Application, handle: SavedStateHandle) : AndroidViewModel(application) {

    val state = MainSearchState()

    /** Runs one search off the main thread. Tests replace it. */
    internal var runSearch: suspend (String) -> List<SearchResult> = ::searchDatabase

    /** Finds the private notes that hold the text, off the main thread. Tests replace it. */
    internal var runNoteSearch: suspend (String) -> List<NoteHit> = ::searchNotes

    /** Finds the blocked (spam) messages that hold the text, off the main thread. Tests replace it. */
    internal var runBlockedSearch: suspend (String) -> List<BlockedHit> = ::searchBlocked

    /** Provider truth (U9): finds the Spam chats that hold the text, off the main thread. Tests replace it. */
    internal var runSpamSearch: suspend (String) -> List<SpamChatHit> = ::searchSpamChats

    private var searchJob: Job? = null

    /** H-8: the chats in Spam, read once per search by the first of its lookups that needs them. */
    @Volatile
    private var querySpam: Lazy<Set<Long>>? = null

    private fun spamIds(app: Application): Set<Long> = querySpam?.value ?: SpamReaders.threadIds(app)

    /** H-16: when the app comes back and the lock covers Spam again, the Spam section goes. */
    private val appReturn = AppReturnWatch(::onAppReturned)

    init {
        appReturn.start()
        state.onClose = { searchJob?.cancel() }
        state.onSpamUnlocked = ::searchSpamAgain
        handle.get<Bundle>(KEY_SAVED)?.let { saved ->
            state.filter = InboxFilter.entries.firstOrNull { it.name == saved.getString(KEY_FILTER) } ?: InboxFilter.All
            state.labelId = saved.getLong(KEY_LABEL, NO_LABEL)
            state.simId = saved.getInt(KEY_SIM, NO_SIM)
            if (saved.getBoolean(KEY_ACTIVE)) {
                state.active = true
                onQueryChange(saved.getString(KEY_QUERY).orEmpty())
            }
        }
        handle.setSavedStateProvider(KEY_SAVED) {
            bundleOf(
                KEY_ACTIVE to state.active, KEY_QUERY to state.query, KEY_FILTER to state.filter.name,
                KEY_LABEL to state.labelId, KEY_SIM to state.simId,
            )
        }
    }

    fun onQueryChange(text: String) {
        state.query = text
        searchJob?.cancel()
        if (!isSearchable(text)) {
            state.results = emptyList()
            state.noteHits = emptyList()
            state.blockedHits = emptyList()
            state.spamHidden = false
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS) // cancelled by the next key press (MC-17)
            val app = getApplication<Application>()
            querySpam = lazy { SpamReaders.threadIds(app) }
            state.results = runSearch(text.trim())
            state.noteHits = runNoteSearch(text.trim())
            if (ProviderTruthFlag.enabled) {
                searchSpam(app, text.trim())
            } else {
                state.blockedHits = runBlockedSearch(text.trim())
            }
        }
    }

    /** Provider truth (U9): the Spam section for [text]; H-20: noted as left out when the lock covers Spam. */
    private suspend fun searchSpam(app: Application, text: String) {
        val spam = runSpamSearch(text)
        state.spamTargets = spam.associate { it.row.address to it.open }
        state.blockedHits = spam.map { it.row }
        state.spamHidden = spam.isEmpty() && SpamReaders.lockedNow(app)
    }

    /**
     * H-20: Spam was unlocked after its section went (or a search ran while it was locked): the query in the field
     * searches Spam again, without retyping. A search still running fills the section itself.
     */
    private fun searchSpamAgain() {
        if (!ProviderTruthFlag.enabled || !isSearchable(state.query) || searchJob?.isActive == true) return
        val text = state.query.trim()
        searchJob = viewModelScope.launch {
            val app = getApplication<Application>()
            querySpam = lazy { SpamReaders.threadIds(app) }
            searchSpam(app, text)
        }
    }

    /** H-16: the app came back to the front; a Spam section found while it was open goes when Spam is locked now. */
    internal fun onAppReturned() {
        if (state.blockedHits.isNotEmpty() && SpamReaders.lockedNow(getApplication())) state.hideSpamHits()
    }

    override fun onCleared() {
        appReturn.stop()
        super.onCleared()
    }

    internal suspend fun searchDatabase(text: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        // B06: nothing of a hidden chat is found, by its title, its number or its text, until it is unlocked.
        val hiddenChats = HiddenChats.get(app)
        // U9, H-1 (provider truth): nothing of a chat in Spam is found here, left out in the SQL before the cap; a
        // failed read of Spam fails the lookup, which then finds nothing.
        val place = SearchPlace.outsideSpam()
        // main-7: the three lookups are independent, so they run side by side.
        val (messages, byTitle, byNumber) = coroutineScope {
            val messagesJob = async {
                // Persian/Arabic letter forms, digits and zero-width marks are folded on both sides (MC-15).
                try {
                    hiddenChats.withoutHidden(app.messagesDB.searchMessages(text, place)) { it.threadId }
                } catch (_: Exception) {
                    emptyList()
                }
            }
            val titleJob = async {
                try {
                    hiddenChats.withoutHidden(app.conversationsDB.searchConversations(text, place)) { it.threadId }
                } catch (_: Exception) {
                    emptyList()
                }
            }
            val numberJob = async {
                // CL-15: a number typed in any form ("0555 345", Persian digits) also finds the chat with that number.
                // Only the plain chat rows are read (no newest-message preview per chat), and only for a number query.
                if (!isNumberQuery(text)) {
                    emptyList()
                } else {
                    try {
                        val all = app.conversationsDB.getAllForNumberSearch()
                        val chats = hiddenChats.withoutHidden(all) { it.threadId }
                        conversationsByNumber(SpamReaders.without(chats, spamIds(app)) { it.threadId }, text)
                    } catch (_: Exception) {
                        emptyList()
                    }
                }
            }
            Triple(messagesJob.await(), titleJob.await(), numberJob.await())
        }
        buildSearchResults(
            conversations = mergeConversationHits(byTitle, byNumber),
            messages = messages,
            formatDate = { seconds -> formatListTimeStyled(app, seconds * 1000L) },
            query = text,
            displayName = { displayTitle(app, it) },
        )
    }

    /**
     * The notes that hold [text], with the title of their chat. Nothing of a hidden chat is found (B06), nor of a chat
     * in Spam (U9); a failing notes table gives no notes, never an error on the search page.
     */
    internal suspend fun searchNotes(text: String): List<NoteHit> = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        try {
            val notes = app.messageNotes
            notes.relink()
            // H-18: the notes of Spam chats are left out in the SQL, before the cap.
            val inPlace = notes.search(text, SearchPlace.outsideSpam())
            val visible = HiddenChats.get(app).withoutHidden(inPlace) { it.threadId }
            val found = SpamReaders.without(visible, spamIds(app)) { it.threadId }
            val chats = found.map { it.threadId }.distinct()
                .mapNotNull { app.conversationsDB.getConversationWithThreadId(it) }
            buildNoteHits(
                notes = found,
                conversations = chats.associateBy { it.threadId },
                query = text,
                formatDate = { seconds -> formatListTimeStyled(app, seconds * 1000L) },
                displayName = { displayTitle(app, it) },
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * The blocked messages that hold [text], one hit per sender (main-22). Nothing is found while the app lock covers
     * only chosen parts and is locked (the Blocked tab is behind it), and a sender who has a chat hidden behind the
     * lock (B06) is not found until it is unlocked. A failing spam store gives no hits, never an error on the page.
     */
    internal suspend fun searchBlocked(text: String): List<BlockedHit> = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        try {
            if (LockScope.blockedLocked(app) && app.appLockManager.isLocked()) return@withContext emptyList()
            val matching = SpamRepositoryProvider.get(app).blocked.first()
                .filter { BlockedFilters.matchesQuery(it, text) }
            if (matching.isEmpty()) return@withContext emptyList()
            val keys = SenderKeys.forDevice(app)
            val hiddenKeys = HiddenChats.get(app).hiddenIds()
                .mapNotNull { app.conversationsDB.getConversationWithThreadId(it) }
                .flatMap { it.phoneNumber.split(',') }
                .map { it.trim() }.filter { it.isNotEmpty() }
                .mapTo(HashSet()) { keys.keyOf(it) }
            buildBlockedHits(
                messages = matching,
                query = text,
                keyOf = keys::keyOf,
                hiddenSenderKeys = hiddenKeys,
                title = { displayNumber(app, it) },
                formatDate = { millis -> formatListTimeStyled(app, millis) },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Provider truth (section 5, U9): the Spam chats that hold [text] in a message, their title or their number, one
     * hit per chat. Nothing is found while the app lock covers Spam and is locked, nor of a chat hidden behind the
     * lock (B06). A failing read gives no hits, never an error on the page. H-1, H-8: the messages and titles are
     * searched in Spam chats only, in the SQL (its own cap); the chats are the plain rows, without previews.
     */
    internal suspend fun searchSpamChats(text: String): List<SpamChatHit> = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        try {
            if (LockScope.blockedLocked(app) && app.appLockManager.isLocked()) return@withContext emptyList()
            val hidden = HiddenChats.get(app)
            val spam = spamIds(app).filterTo(HashSet()) { !hidden.isHidden(it) }
            if (spam.isEmpty()) return@withContext emptyList()
            val chatOf = app.conversationsDB.getAllForNumberSearch().filter { it.threadId in spam }
                .associateBy { it.threadId }
            val byTitle = app.conversationsDB.searchConversations(text, SearchPlace.SPAM)
                .filter { it.threadId in chatOf }
            val byNumber = if (isNumberQuery(text)) conversationsByNumber(chatOf.values.toList(), text) else emptyList()
            buildSpamChatHits(
                chats = mergeConversationHits(byTitle, byNumber),
                messages = app.messagesDB.searchMessages(text, SearchPlace.SPAM).filter { it.threadId in chatOf },
                chatOf = chatOf,
                query = text,
                title = { displayTitle(app, it) },
                formatDate = { seconds -> formatListTimeStyled(app, seconds * 1000L) },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    internal companion object {
        const val KEY_SAVED = "main_search"
        const val KEY_ACTIVE = "active"
        const val KEY_QUERY = "query"
        const val KEY_FILTER = "filter"
        const val KEY_LABEL = "label"
        const val KEY_SIM = "sim"
    }
}
