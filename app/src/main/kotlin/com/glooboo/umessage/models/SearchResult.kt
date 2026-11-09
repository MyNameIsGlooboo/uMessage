package com.glooboo.umessage.models

data class SearchResult(
    val messageId: Long,
    val title: String,
    val snippet: String,
    val date: String,
    val threadId: Long,
    var photoUri: String,
    /** With [messageId], which message was found (see [MessageKey]). */
    val isMMS: Boolean = false,
    /**
     * The number to write to, for a contact found by name that has no conversation yet: the chat is opened with it,
     * as the new-conversation picker does, because the phone knows no participants for such a thread.
     */
    val number: String = "",
)
