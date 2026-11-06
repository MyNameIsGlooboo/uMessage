package com.glooboo.umessage.ui.main

import com.glooboo.umessage.models.SearchResult
import com.glooboo.umessage.ui.conversations.ConversationRow

/** A conversation of the filtered inbox, shown as a search hit on the conversation itself. */
internal fun conversationHit(row: ConversationRow) = SearchResult(
    messageId = CONVERSATION_HIT,
    title = row.title,
    snippet = row.snippet,
    date = row.dateLabel,
    threadId = row.threadId,
    photoUri = row.photoUri,
)
