package com.glooboo.umessage.ui.main

import android.app.Application
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.helpers.HiddenChats
import com.glooboo.umessage.helpers.messageNotes
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

    private var searchJob: Job? = null

    init {
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
            return
        }
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS) // cancelled by the next key press (MC-17)
            state.results = runSearch(text.trim())
            state.noteHits = runNoteSearch(text.trim())
            state.blockedHits = runBlockedSearch(text.trim())
        }
    }

    internal suspend fun searchDatabase(text: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        // B06: nothing of a hidden chat is found, by its title, its number or its text, until it is unlocked.
        val hiddenChats = HiddenChats.get(app)
        // main-7: the three lookups are independent, so they run side by side.
        val (messages, byTitle, byNumber) = coroutineScope {
            val messagesJob = async {
                // Persian/Arabic letter forms, digits and zero-width marks are folded on both sides (MC-15).
                try {
                    hiddenChats.withoutHidden(app.messagesDB.searchMessages(text)) { it.threadId }
                } catch (_: Exception) {
                    emptyList()
                }
            }
            val titleJob = async {
                try {
                    hiddenChats.withoutHidden(app.conversationsDB.searchConversations(text)) { it.threadId }
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
                        conversationsByNumber(
                            hiddenChats.withoutHidden(app.conversationsDB.getAllForNumberSearch()) { it.threadId }, text
                        )
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
     * The notes that hold [text], with the title of their chat. Nothing of a hidden chat is found (B06); a failing
     * notes table gives no notes, never an error on the search page.
     */
    internal suspend fun searchNotes(text: String): List<NoteHit> = withContext(Dispatchers.IO) {
        val app = getApplication<Application>()
        try {
            val notes = app.messageNotes
            notes.relink()
            val found = HiddenChats.get(app).withoutHidden(notes.search(text)) { it.threadId }
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

    internal companion object {
        const val KEY_SAVED = "main_search"
        const val KEY_ACTIVE = "active"
        const val KEY_QUERY = "query"
        const val KEY_FILTER = "filter"
        const val KEY_LABEL = "label"
        const val KEY_SIM = "sim"
    }
}
