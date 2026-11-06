package com.glooboo.umessage.ui.labels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glooboo.umessage.R
import com.glooboo.umessage.models.Label
import com.glooboo.umessage.ui.icons.Symbols

private const val NONE = -1L

/**
 * I29: the list of labels with Rename and Delete on each row and Add at the bottom. Deleting asks first and removes
 * only the label; its conversations and messages stay. The rows scroll, the Add button stays in view.
 */
@Composable
internal fun LabelManagerDialog(onDismiss: () -> Unit, viewModel: LabelsViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableLongStateOf(NONE) }
    var deletingId by rememberSaveable { mutableLongStateOf(NONE) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.i29_manager_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LabelItemGap)) {
                LabelDialogNote(stringResource(R.string.i29_manager_note))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    if (state.labels.isEmpty()) LabelDialogNote(stringResource(R.string.i29_picker_empty))
                    state.labels.forEach { label ->
                        LabelManagerRow(label, onEdit = { editingId = label.id }, onDelete = { deletingId = label.id })
                    }
                }
                if (state.labels.size < LabelRules.MAX_LABELS) {
                    LabelAddButton(stringResource(R.string.i29_tile_add)) { adding = true }
                } else {
                    LabelDialogNote(stringResource(R.string.i29_error_limit, LabelRules.MAX_LABELS))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.i29_done)) } },
    )
    if (adding) {
        LabelEditDialog(null, state.labels, { name, color -> viewModel.add(name, color) }, { adding = false })
    }
    state.label(editingId)?.let { label ->
        LabelEditDialog(
            initial = label,
            labels = state.labels,
            onSave = { name, color -> viewModel.update(label.id, name, color) },
            onDismiss = { editingId = NONE },
        )
    }
    state.label(deletingId)?.let { label ->
        DeleteLabelDialog(
            name = label.name,
            onConfirm = {
                deletingId = NONE
                viewModel.delete(label.id)
            },
            onDismiss = { deletingId = NONE },
        )
    }
}

@Composable
internal fun DeleteLabelDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.i29_delete_title)) },
        text = { Text(stringResource(R.string.i29_delete_body, name)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.i29_delete_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.i29_cancel)) } },
    )
}

@Composable
private fun LabelManagerRow(label: Label, onEdit: () -> Unit, onDelete: () -> Unit) {
    val iconTint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().heightIn(min = LabelRowHeight), verticalAlignment = Alignment.CenterVertically) {
        LabelDot(label.color)
        Spacer(Modifier.width(LabelItemGap))
        Text(
            label.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onEdit) {
            Icon(Symbols.Edit, stringResource(R.string.i29_manager_edit, label.name), tint = iconTint)
        }
        IconButton(onClick = onDelete) {
            Icon(Symbols.Delete, stringResource(R.string.i29_manager_delete, label.name), tint = iconTint)
        }
    }
}
