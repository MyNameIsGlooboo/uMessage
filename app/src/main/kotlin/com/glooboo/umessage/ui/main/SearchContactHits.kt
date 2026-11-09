package com.glooboo.umessage.ui.main

import com.glooboo.umessage.models.SearchResult

/** The address a chat with a contact's [number] is made for: as written, without spaces, dashes and brackets. */
internal fun contactThreadAddress(number: String): String =
    number.filter { it.isDigit() || it == '+' }.ifEmpty { number.trim() }

/** The search result that opens the chat of [hit] in thread [threadId], with the number for a chat with no messages. */
internal fun contactHitResult(hit: ContactHit, threadId: Long): SearchResult =
    SearchResult(
        CONVERSATION_HIT,
        hit.contact.name,
        "",
        "",
        threadId,
        hit.contact.photoUri,
        number = contactThreadAddress(hit.number),
    )
