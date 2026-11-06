package com.glooboo.umessage.ui.thread

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.MessageNotes
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.ui.components.localizedNumber
import com.glooboo.umessage.ui.components.uiLocale

private const val NOTE_MIN_LINES = 3
private const val NOTE_FIELD_MAX_LINES = 8

/** [value] cut to [MessageNotes.MAX_LENGTH] characters, with the cursor at the end when it had to be cut. */
fun cappedNote(value: TextFieldValue): TextFieldValue =
    if (value.text.length <= MessageNotes.MAX_LENGTH) {
        value
    } else {
        value.copy(text = value.text.take(MessageNotes.MAX_LENGTH), selection = TextRange(MessageNotes.MAX_LENGTH))
    }

/** The edit dialog of the note of [message], over the screen's [notes]. */
@Composable
fun NoteEditDialog(notes: ThreadNotes, message: Message) {
    val existing = remember(message) { notes.noteOf(message)?.text }
    NoteEditDialog(
        initial = existing.orEmpty(),
        hasNote = existing != null,
        onSave = notes::save,
        onDelete = notes::delete,
        onDismiss = notes::dismiss,
    )
}

/**
 * Add or edit a note: a multi-line field (Persian and Arabic text run right to left on their own), the counter
 * "n/500", Save, Cancel and, when the message has a note, Delete note. Saving blank text deletes the note.
 */
@Composable
fun NoteEditDialog(
    initial: String,
    hasNote: Boolean,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val locale = uiLocale()
    val counter = stringResource(
        R.string.note_counter,
        localizedNumber(value.text.length, locale),
        localizedNumber(MessageNotes.MAX_LENGTH, locale),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (hasNote) R.string.edit_note else R.string.add_note)) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = cappedNote(it) },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                    placeholder = { Text(stringResource(R.string.note_hint)) },
                    minLines = NOTE_MIN_LINES,
                    maxLines = NOTE_FIELD_MAX_LINES,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    supportingText = {
                        Text(counter, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End)
                    },
                )
                if (hasNote) {
                    TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.delete_note), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(value.text) }) { Text(stringResource(R.string.note_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.note_cancel)) }
        },
    )
}
