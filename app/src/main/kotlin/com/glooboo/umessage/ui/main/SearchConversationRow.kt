package com.glooboo.umessage.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.models.SearchResult
import com.glooboo.umessage.ui.components.rememberCodeHidden
import com.glooboo.umessage.ui.conversations.ContactAvatar
import com.glooboo.umessage.ui.conversations.RowLabel
import com.glooboo.umessage.ui.conversations.RowLabelChips
import com.glooboo.umessage.ui.theme.contentDirection

/**
 * A search result as a conversation row, like the inbox's: avatar, title, the match in the snippet (highlighted in the
 * theme's colour), the labels, and at the far end the time and the unread count.
 */
@Composable
internal fun ConversationResultRow(
    hit: ConversationHitRow,
    query: String,
    labels: List<RowLabel>,
    onOpen: (SearchResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val highlight = SpanStyle(color = colors.primary, fontWeight = FontWeight.Bold)
    val unread = hit.row?.unread == true
    val weight = if (unread) FontWeight.Bold else FontWeight.Normal
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onOpen(hit.open) }
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ContactAvatar(title = hit.title, photoUri = hit.photoUri, isGroup = hit.row?.isGroup == true)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                highlightedText(hit.title, query, TitleMatchStyle),
                style = MaterialTheme.typography.titleMedium,
                color = colors.onSurface,
                fontWeight = weight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (hit.snippet.isNotEmpty()) {
                Text(
                    highlightedText(rememberCodeHidden(hit.snippet), query, highlight),
                    style = MaterialTheme.typography.bodyMedium.contentDirection(),
                    color = if (unread) colors.onSurface else colors.onSurfaceVariant,
                    fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            RowLabelChips(labels, Modifier.padding(top = 2.dp))
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                hit.date,
                style = MaterialTheme.typography.labelMedium,
                color = if (unread) colors.primary else colors.onSurfaceVariant,
                fontWeight = weight,
                maxLines = 1,
            )
            if (unread) UnreadCount(hit.row.unreadBadge.orEmpty())
        }
    }
}

/** The unread count as a small circle; a dot when the count is not known. */
@Composable
private fun UnreadCount(label: String) {
    val colors = MaterialTheme.colorScheme
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = colors.onPrimary,
        maxLines = 1,
        modifier = Modifier
            .clearAndSetSemantics { }
            .clip(CircleShape)
            .background(colors.primary)
            .padding(horizontal = if (label.isEmpty()) 4.dp else 5.dp, vertical = if (label.isEmpty()) 4.dp else 1.dp),
    )
}

/** The "Show more" row at the end of the first results. */
@Composable
internal fun ShowMoreRow(onShow: () -> Unit) {
    TextButton(
        onClick = onShow,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 8.dp),
    ) {
        Text(stringResource(R.string.search_page_show_more))
    }
}

/** [text] with every match of [query] in the highlight [style]. */
internal fun highlightedText(text: String, query: String, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(text)
    highlightRanges(text, query).forEach { addStyle(style, it.first, it.last + 1) }
}
