package com.glooboo.umessage.ui.newconversation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.stripControlCharacters
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.ui.components.displayTitle
import com.glooboo.umessage.ui.components.formatListTimeStyled
import com.glooboo.umessage.ui.components.isolateNumbers
import com.glooboo.umessage.ui.conversations.ContactAvatar
import com.glooboo.umessage.ui.conversations.conversationPreview

/** The avatar of a share-target row: smaller than the 48dp one of the main list, so eight rows stay compact. */
private val CompactAvatarSize = 40.dp

/** What a "Recent chats" row shows, prepared once per chat. */
private class RecentChatText(val title: String, val snippet: String, val date: String) {
    /** TalkBack reads the row as the main list does: title, preview, date. */
    val description: String = listOf(title, snippet, date).filter { it.isNotBlank() }.joinToString(", ")
}

/**
 * A compact conversation row for the share picker: avatar (same tones as the main list), title, a one-line preview
 * and the date at the end. Taps open the chat.
 */
@Composable
internal fun RecentChatRow(chat: Conversation, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val noTextLabel = stringResource(R.string.attachment)
    val text = remember(chat, noTextLabel) {
        RecentChatText(
            title = displayTitle(context, chat.title.stripControlCharacters()),
            snippet = conversationPreview(chat.snippet, null, noTextLabel),
            date = formatListTimeStyled(context, chat.date * 1000L),
        )
    }
    // Each text reads in its own direction and sits against the avatar's side (start/end follow the layout).
    val align = if (LocalLayoutDirection.current == LayoutDirection.Rtl) TextAlign.Right else TextAlign.Left
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = text.description }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ContactAvatar(
            title = text.title,
            photoUri = chat.photoUri,
            isGroup = chat.isGroupConversation,
            size = CompactAvatarSize,
        )
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = isolateNumbers(text.title),
                    style = MaterialTheme.typography.titleMedium.copy(
                        textDirection = TextDirection.Content,
                        textAlign = align,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = text.date,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
            Text(
                text = isolateNumbers(text.snippet),
                style = MaterialTheme.typography.bodyMedium.copy(
                    textDirection = TextDirection.Content,
                    textAlign = align,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
            )
        }
    }
}
