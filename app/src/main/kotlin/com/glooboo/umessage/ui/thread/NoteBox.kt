package com.glooboo.umessage.ui.thread

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.models.MessageNote
import com.glooboo.umessage.ui.icons.Symbols

/** A note longer than this many lines is cut with an ellipsis; the edit dialog shows all of it. */
internal const val NOTE_MAX_LINES = 6

private val NoteShape = RoundedCornerShape(12.dp)
private val NoteIconSize = 16.dp

/**
 * The private note of a message: a tinted box stuck under the bubble, in a colour no bubble has, with a small note
 * icon. The parent column aligns it with the bubble (start and end, so it follows the layout direction); it is as
 * wide as a bubble at most, and a tap calls [onClick] (the edit dialog).
 */
@Composable
fun NoteBox(text: String, maxWidth: Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val description = stringResource(R.string.note_content_desc, text)
    Surface(
        shape = NoteShape,
        color = colors.tertiaryContainer,
        contentColor = colors.onTertiaryContainer,
        modifier = modifier
            .padding(top = 2.dp)
            .widthIn(max = maxWidth)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .clickable(onClickLabel = stringResource(R.string.edit_note), role = Role.Button, onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .clearAndSetSemantics { },
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Symbols.Description,
                contentDescription = null,
                modifier = Modifier.padding(top = 2.dp).size(NoteIconSize),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                maxLines = NOTE_MAX_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The [note] of a message under its bubble, when it has one; a tap calls [onClick]. */
@Composable
internal fun NoteUnderBubble(note: MessageNote?, maxWidth: Dp, onClick: () -> Unit) {
    if (note != null) NoteBox(text = note.text, maxWidth = maxWidth, onClick = onClick)
}
