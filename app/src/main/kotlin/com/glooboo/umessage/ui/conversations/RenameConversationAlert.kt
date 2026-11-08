package com.glooboo.umessage.ui.conversations

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.theme.contentDirection
import com.glooboo.umessage.ui.components.dialogScroll

/**
 * Rename dialog shared by the list's selection bar, the details screen and the thread (CL-28, CL-29).
 * The field has a floating label and a clear button, all text is selected when it opens, "Save" stays off while
 * the name is blank or unchanged, and "Reset to default name" (when [onReset] is given) hands the sender's own
 * name back. [hint] is the one-line note on who sees the name, shown here only (CL-27).
 */
@Composable
fun RenameConversationAlert(
    title: String,
    initial: String,
    placeholder: String,
    hint: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
    onReset: (() -> Unit)? = null,
) {
    var value by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val trimmed = value.text.trim()
    val canSave = trimmed.isNotEmpty() && trimmed != initial.trim()
    val save = { if (canSave) onSave(trimmed) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.dialogScroll()) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.contentDirection(),
                    label = { Text(stringResource(R.string.convx_name_label)) },
                    placeholder = if (placeholder.isNotBlank()) ({ Text(placeholder) }) else null,
                    supportingText = { Text(hint) },
                    trailingIcon = if (value.text.isNotEmpty()) {
                        {
                            IconButton(onClick = { value = TextFieldValue("") }) {
                                Icon(Symbols.Close, contentDescription = stringResource(R.string.convx_clear))
                            }
                        }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                )
                if (onReset != null) {
                    TextButton(onClick = onReset) { Text(stringResource(R.string.convx_reset_name)) }
                }
            }
        },
        confirmButton = {
            FilledTonalButton(onClick = save, enabled = canSave) { Text(stringResource(R.string.convx_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.convlist_cancel)) } },
    )
}
