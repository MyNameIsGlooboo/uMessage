package com.glooboo.umessage.ui.thread

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.SavedText
import com.glooboo.umessage.helpers.SavedTexts
import com.glooboo.umessage.helpers.composerPrefs
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.theme.contentDirection
import kotlinx.coroutines.launch
import com.glooboo.umessage.ui.components.ConfirmDialog
import com.glooboo.umessage.ui.components.DeleteStartActions
import com.glooboo.umessage.ui.components.dialogScroll

/** The saved text being added ([adding]) or changed ([editing]); only one dialog is open at a time. */
private data class SavedTextEdit(val editing: SavedText? = null, val adding: Boolean = false)

/**
 * The "Saved text" sheet of the attach menu (N06): a tap puts the whole text in the message field at the cursor,
 * never sends it. The list is managed here: add, change (pencil or long-press) and delete.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SavedTextSheet(onDismiss: () -> Unit, onInsert: (String) -> Unit) {
    val prefs = LocalContext.current.composerPrefs
    val store = remember { SavedTexts(prefs) }
    var items by remember { mutableStateOf(store.all()) }
    var edit by remember { mutableStateOf<SavedTextEdit?>(null) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.n06_sheet_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            if (items.isEmpty()) {
                Text(
                    stringResource(R.string.n06_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                items(items, key = { it.id }) { item ->
                    SavedTextRow(
                        item,
                        onInsert = { scope.launch { state.hide() }.invokeOnCompletion { onInsert(item.text) } },
                        onEdit = { edit = SavedTextEdit(editing = item) },
                    )
                }
            }
            TextButton(
                onClick = { edit = SavedTextEdit(adding = true) },
                enabled = items.size < SavedTexts.MAX_COUNT,
                modifier = Modifier.padding(horizontal = 12.dp),
            ) {
                Icon(Symbols.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.n06_add), modifier = Modifier.padding(start = 8.dp))
            }
            Text(
                stringResource(R.string.n06_private_tip),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
        }
    }
    edit?.let { current ->
        SavedTextDialog(
            initial = current.editing,
            onSave = { title, text ->
                if (current.editing == null) store.add(title, text) else store.update(current.editing.id, title, text)
                items = store.all()
                edit = null
            },
            onDelete = current.editing?.let {
                {
                    store.delete(it.id)
                    items = store.all()
                    edit = null
                }
            },
            onDismiss = { edit = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedTextRow(item: SavedText, onInsert: () -> Unit, onEdit: () -> Unit) {
    ListItem(
        headlineContent = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(item.text.lineSequence().first(), maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        trailingContent = {
            IconButton(onClick = onEdit) {
                Icon(Symbols.Edit, contentDescription = stringResource(R.string.n06_edit))
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.combinedClickable(onClick = onInsert, onLongClick = onEdit),
    )
}

/**
 * Title and text of a saved text; [onDelete] is null for a new one. Saving stays off while the text is blank. Delete
 * sits red at the start of the action row, Cancel and Save at the end, and Delete asks first (there is no Undo).
 */
@Composable
internal fun SavedTextDialog(
    initial: SavedText?,
    onSave: (title: String, text: String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var title by rememberSaveable { mutableStateOf(initial?.title.orEmpty()) }
    var text by rememberSaveable { mutableStateOf(initial?.text.orEmpty()) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    val style = LocalTextStyle.current.contentDirection()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.n06_add else R.string.n06_edit)) },
        text = {
            Column(Modifier.dialogScroll(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it.take(SavedTexts.MAX_TITLE_LENGTH) },
                    label = { Text(stringResource(R.string.n06_title_label)) },
                    singleLine = true,
                    textStyle = style,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(SavedTexts.MAX_TEXT_LENGTH) },
                    label = { Text(stringResource(R.string.n06_text_label)) },
                    minLines = 3,
                    maxLines = 6,
                    textStyle = style,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    supportingText = { Text(stringResource(R.string.n06_private_tip)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            DeleteStartActions(
                deleteLabel = stringResource(R.string.n06_delete),
                onDelete = onDelete?.let { { confirming = true } },
                cancelLabel = stringResource(R.string.screens_cancel),
                onCancel = onDismiss,
                saveLabel = stringResource(R.string.n06_save),
                onSave = { onSave(title, text) },
                saveEnabled = text.isNotBlank(),
            )
        },
    )
    if (confirming && onDelete != null) {
        ConfirmDialog(
            title = stringResource(R.string.p44_delete_saved_title),
            message = title.ifBlank { text.lineSequence().first() },
            confirmLabel = stringResource(R.string.n06_delete),
            onConfirm = onDelete,
            onDismiss = { confirming = false },
            destructive = true,
        )
    }
}
