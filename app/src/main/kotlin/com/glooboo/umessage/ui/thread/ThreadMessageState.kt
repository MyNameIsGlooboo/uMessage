package com.glooboo.umessage.ui.thread

import com.glooboo.umessage.extensions.getExtensionFromMimeType
import com.glooboo.umessage.extensions.isImageMimeType
import com.glooboo.umessage.models.Attachment
import com.glooboo.umessage.models.Message

/**
 * The thread's loaded messages. Loading, paging, sending and the refresh after every new message all run on
 * different background threads; each change goes through [update], so one change can no longer overwrite another
 * that landed in between (a message that "vanished until refresh").
 */
class ThreadMessageList {
    private val lock = Any()

    @Volatile
    var current: List<Message> = emptyList()
        private set

    fun set(messages: List<Message>) {
        synchronized(lock) { current = messages }
    }

    /** Replaces the list with [transform] of the latest list, atomically against every other change. */
    fun update(transform: (List<Message>) -> List<Message>): List<Message> = synchronized(lock) {
        transform(current).also { current = it }
    }
}

/**
 * The list after a refresh. [fresh] is the newest page from the provider (plus scheduled messages); unless it is
 * the [whole thread][freshIsWholeThread], older messages already paged in are kept, so a new message or a
 * delivery report no longer cuts the list back to the newest page while the user is reading old history.
 */
fun mergeRefreshedMessages(current: List<Message>, fresh: List<Message>, freshIsWholeThread: Boolean): List<Message> {
    if (freshIsWholeThread) return fresh
    val oldestFresh = fresh.filterNot { it.isScheduled }.minOfOrNull { it.date } ?: return fresh
    val freshIds = fresh.mapTo(HashSet()) { it.getStableId() }
    val older = current.filter { !it.isScheduled && it.date <= oldestFresh && it.getStableId() !in freshIds }
    return older + fresh
}

/**
 * Whether paging found the start of the thread. An empty page alone does not prove it: the provider query swallows
 * its errors and returns nothing, which used to stop loading older messages for good. [probeHasOlder] is a direct
 * check: null when it failed, so the next scroll tries again.
 */
fun isHistoryExhausted(pageSize: Int, probeHasOlder: Boolean?): Boolean = pageSize == 0 && probeHasOlder == false

/** The composer is emptied only for a message typed there that was really handed to the system. */
fun shouldClearComposerAfterSend(handedOff: Boolean, fromComposer: Boolean): Boolean = handedOff && fromComposer

/**
 * The draft text to keep when leaving the thread, or null to delete the stored draft. A draft with a photo used to
 * be deleted outright; its text is kept now (attachments cannot be stored in an SMS draft).
 */
fun draftToSave(text: String): String? = text.trim().ifEmpty { null }

/** File name offered when saving an MMS attachment: its own name, else the uri's last part with an extension. */
fun saveFileName(attachment: Attachment): String {
    attachment.filename.takeIf { it.isNotBlank() }?.let { return it }
    val base = attachment.uriString.trimEnd('/').substringAfterLast('/').ifBlank { "attachment" }
    val mime = attachment.mimetype
    return if (!base.contains('.') && mime.isImageMimeType()) base + mime.getExtensionFromMimeType() else base
}
