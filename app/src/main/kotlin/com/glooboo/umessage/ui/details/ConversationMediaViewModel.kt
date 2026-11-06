package com.glooboo.umessage.ui.details

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.ui.main.SearchShortcuts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * I56: reads one conversation's pictures and links from the message cache on the IO thread, once. The queries only
 * prefilter in SQL ([SearchShortcuts.CANDIDATES] rows at most); no new table.
 */
class ConversationMediaViewModel(application: Application) : AndroidViewModel(application) {

    /** Null until the first read is done. */
    var media by mutableStateOf<ConversationMedia?>(null)
        private set

    private var loadedThread = NO_THREAD

    fun load(threadId: Long) {
        if (loadedThread == threadId) return
        loadedThread = threadId
        viewModelScope.launch { media = withContext(Dispatchers.IO) { read(threadId) } }
    }

    private fun read(threadId: Long): ConversationMedia = runCatching {
        val db = getApplication<Application>().messagesDB
        ConversationMedia(
            images = mediaImages(db.getThreadImageCandidates(threadId, SearchShortcuts.CANDIDATES)),
            links = mediaLinks(db.getThreadLinkCandidates(threadId, SearchShortcuts.CANDIDATES)),
        )
    }.getOrDefault(ConversationMedia(emptyList(), emptyList()))

    private companion object {
        const val NO_THREAD = -1L
    }
}
