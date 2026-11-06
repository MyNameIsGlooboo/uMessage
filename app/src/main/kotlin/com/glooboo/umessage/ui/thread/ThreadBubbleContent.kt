package com.glooboo.umessage.ui.thread

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.isImageMimeType
import com.glooboo.umessage.extensions.isPlayableAudioMimeType
import com.glooboo.umessage.extensions.isVCardMimeType
import com.glooboo.umessage.extensions.isVideoMimeType
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.theme.contentDirection

/** Media never takes more than this width, however wide the bubble may grow. */
private val MaxMediaWidth = 300.dp

/** Share of the bubble's text color in the text selection highlight (see [bubbleSelectionColors]). */
internal const val BUBBLE_SELECTION_ALPHA = 0.35f

/**
 * Owner notes R2: the colors for selecting part of the selected bubble's text. The M3 default (primary at 40%) is a
 * muted purple on the selected bubble's tertiaryContainer and could hardly be seen. The highlight is the bubble's own
 * [content] (text) color at [BUBBLE_SELECTION_ALPHA] over the bubble, so in every theme it lies between the bubble and
 * the text: clearly darker (light themes) or lighter (dark themes) than the bubble, and the text stays readable on it.
 * The handles take the [accent], like the selected bubble's outline.
 */
internal fun bubbleSelectionColors(content: Color, accent: Color): TextSelectionColors =
    TextSelectionColors(handleColor = accent, backgroundColor = content.copy(alpha = BUBBLE_SELECTION_ALPHA))

/** The bubble's text with its links (TH-05) and, while searching, the matches highlighted (TH-20). */
@Composable
private fun rememberBubbleText(
    message: Message,
    selected: Boolean,
    query: String,
    contentColor: Color,
): AnnotatedString {
    val colors = MaterialTheme.colorScheme
    val linkColor = bubbleLinkColor(!message.isReceivedMessage(), selected, contentColor, colors.primary)
    val highlight = SpanStyle(background = colors.tertiary, color = colors.onTertiary)
    return remember(message.body, linkColor, query, highlight) {
        highlightMatches(linkify(message.body, linkColor), query, highlight)
    }
}

/**
 * What a bubble holds: its attachments, its text (selectable in place while it is the one selected bubble) and, for a
 * message scheduled with an inexact alarm, a note that it may go out late ([approximateTime]), and for one that
 * repeats, the note [repeatNote] names.
 */
@Composable
internal fun BubbleContent(
    message: Message,
    actions: ThreadActions,
    contentColor: Color,
    maxBubbleWidth: Dp,
    textSelectable: Boolean,
    selecting: Boolean,
    selected: Boolean,
    searchQuery: String,
    approximateTime: Boolean,
    repeatNote: Int?,
    onToggleSelect: () -> Unit,
) {
    val gestures = AttachmentGestures(
        selecting = selecting,
        onToggleSelect = onToggleSelect,
        onLongClick = onToggleSelect,
    )
    Column {
        BubbleAttachments(message, actions, gestures, mediaWidth = minOf(maxBubbleWidth, MaxMediaWidth))
        if (message.body.isNotEmpty()) {
            val text = rememberBubbleText(message, selected, searchQuery, contentColor)
            BubbleText(message, text, contentColor, textSelectable)
        }
        // RM-27: scheduled with an inexact alarm ("Schedule anyway"), so it may go out a few minutes late.
        val notes = listOfNotNull(
            R.string.thread_scheduled_approximate.takeIf {
                message.isScheduled && !message.isHeldScheduled() && approximateTime
            },
            repeatNote,
        )
        notes.forEachIndexed { index, note ->
            Text(
                stringResource(note),
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                modifier = Modifier.padding(
                    start = 14.dp,
                    end = 14.dp,
                    top = if (index == 0 && message.body.isEmpty()) 8.dp else 0.dp,
                    bottom = if (index == notes.lastIndex) 8.dp else 2.dp,
                ),
            )
        }
    }
}

/** A bubble's attachments, each drawn by its type: media, audio, a contact card, or any other file. */
@Composable
private fun BubbleAttachments(message: Message, actions: ThreadActions, gestures: AttachmentGestures, mediaWidth: Dp) {
    message.attachment?.attachments?.forEach { attachment ->
        val mimeType = attachment.mimetype
        val uri = attachment.getUri()
        val open = { actions.onOpenFile(uri, mimeType, attachment.filename) }
        when {
            mimeType.isImageMimeType() || mimeType.isVideoMimeType() ->
                MessageMedia(uri, mimeType.isVideoMimeType(), mediaWidth, gestures, onOpen = open)

            mimeType.isPlayableAudioMimeType() ->
                AudioAttachment(uri, gestures, onOpenWith = open, modifier = Modifier.padding(6.dp))

            mimeType.isVCardMimeType() ->
                VCardAttachment(uri, gestures, onOpen = { actions.onOpenVCard(uri) }, modifier = Modifier.padding(6.dp))

            else -> DocumentAttachment(
                uri,
                attachment.filename,
                mimeType,
                gestures,
                onOpen = open,
                modifier = Modifier.padding(6.dp),
            )
        }
    }
}

/** The bubble's text, italic with a clock for a scheduled message. */
@Composable
private fun BubbleText(message: Message, text: AnnotatedString, contentColor: Color, textSelectable: Boolean) {
    val base = MaterialTheme.typography.bodyLarge.contentDirection()
    Row(
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val style = if (message.isScheduled) base.copy(fontStyle = FontStyle.Italic) else base
        Box(modifier = Modifier.weight(1f, fill = false)) {
            // Links stay tappable inside the SelectionContainer: they go through the same link sheet.
            if (textSelectable) {
                val selectionColors = bubbleSelectionColors(contentColor, MaterialTheme.colorScheme.primary)
                CompositionLocalProvider(LocalTextSelectionColors provides selectionColors) {
                    SelectionContainer { Text(text, style = style, color = contentColor) }
                }
            } else {
                Text(text, style = style, color = contentColor)
            }
        }
        if (message.isScheduled) {
            Icon(
                Symbols.Schedule,
                contentDescription = stringResource(R.string.thread_scheduled_message),
                tint = contentColor,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .size(16.dp),
            )
        }
    }
}
