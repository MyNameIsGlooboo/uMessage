package com.glooboo.umessage.ui.newconversation

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.res.stringResource
import com.glooboo.umessage.R
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.ui.components.SectionHeader

/* The share-mode pieces of the new-conversation picker, kept out of the (already large) screen function. */

/** The screen title: the group step, the share picker, or the plain new conversation. */
internal fun pickerTitle(groupMode: Boolean, shareMode: Boolean): Int = when {
    groupMode -> R.string.screens_new_group_title
    shareMode -> R.string.share_picker_title
    else -> R.string.new_conversation
}

/** The chats of the "Recent chats" section: only when something is shared, narrowed by the search text. */
internal fun shareTargetsFor(shareMode: Boolean, chats: List<Conversation>, query: String): List<Conversation> =
    if (shareMode) filterShareTargets(chats, query) else emptyList()

/** True when the search found chats but no contact: the chats are the answer, so no "no matches" is shown. */
internal fun onlyRecentsMatch(noContacts: Boolean, query: String, recents: List<Conversation>): Boolean =
    noContacts && query.isNotBlank() && recents.isNotEmpty()

/** The "Recent chats" header and rows; nothing when [chats] is empty. */
internal fun LazyListScope.recentChatsSection(chats: List<Conversation>, onPick: (Conversation) -> Unit) {
    if (chats.isEmpty()) return
    item(key = "recent-header") { SectionHeader(stringResource(R.string.share_recent_chats)) }
    items(chats, key = { "recent-${it.threadId}" }) { chat -> RecentChatRow(chat, onClick = { onPick(chat) }) }
}

/** The "Contacts" header above search results, needed only when a Recent chats section sits above them. */
internal fun LazyListScope.contactsHeader(visible: Boolean) {
    if (visible) item(key = "contacts-header") { SectionHeader(stringResource(R.string.screens_new_contacts)) }
}
