package com.glooboo.umessage.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.labels.DeleteLabelDialog
import com.glooboo.umessage.ui.labels.LabelChipActions
import com.glooboo.umessage.ui.labels.LabelEditDialog
import com.glooboo.umessage.ui.labels.LabelManagerDialog
import com.glooboo.umessage.ui.labels.LabelsViewModel
import com.glooboo.umessage.ui.labels.SearchLabelChips
import com.glooboo.umessage.ui.labels.forgetLabel
import com.glooboo.umessage.ui.labels.selectedLabelId
import com.glooboo.umessage.ui.labels.toggleLabel

private const val NONE = -1L

/**
 * The "Labels" section of the search page: a heading with Edit (the label manager) and Add beside it, and the labels as
 * wrapping chips. With no label yet, only Add and a line of hint. Nothing is shown until the labels are read.
 */
@Composable
internal fun SearchLabelRow(
    search: MainSearchState,
    modifier: Modifier = Modifier,
    viewModel: LabelsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    var managing by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableLongStateOf(NONE) }
    var deletingId by rememberSaveable { mutableLongStateOf(NONE) }
    if (!state.loaded) return
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LabelsHeader(hasLabels = state.labels.isNotEmpty(), onManage = { managing = true }, onAdd = { adding = true })
        if (state.labels.isEmpty()) {
            Text(
                stringResource(R.string.i29_picker_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        } else {
            SearchLabelChips(
                labels = state.labels,
                selectedId = search.selectedLabelId,
                actions = LabelChipActions(
                    onToggle = { search.toggleLabel(it) },
                    onEdit = { editingId = it },
                    onDelete = { deletingId = it },
                ),
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
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
                search.forgetLabel(label.id)
                viewModel.delete(label.id)
            },
            onDismiss = { deletingId = NONE },
        )
    }
    if (managing) LabelManagerDialog(onDismiss = { managing = false })
}

/** The heading with the pencil (manage) and the plus (add) at its end; the pencil only while there are labels. */
@Composable
private fun LabelsHeader(hasLabels: Boolean, onManage: () -> Unit, onAdd: () -> Unit) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.i29_picker_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (hasLabels) {
            IconButton(onClick = onManage) {
                Icon(Symbols.Edit, contentDescription = stringResource(R.string.i29_tiles_manage), tint = tint)
            }
        }
        IconButton(onClick = onAdd) {
            Icon(Symbols.Add, contentDescription = stringResource(R.string.i29_tile_add), tint = tint)
        }
    }
}
