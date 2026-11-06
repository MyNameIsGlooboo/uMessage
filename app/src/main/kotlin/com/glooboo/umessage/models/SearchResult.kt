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
)
