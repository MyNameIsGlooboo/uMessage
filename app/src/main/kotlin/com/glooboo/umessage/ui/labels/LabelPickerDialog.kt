package com.glooboo.umessage.ui.labels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glooboo.umessage.R
import com.glooboo.umessage.models.Label

/**
 * I29: ticks the labels of [threadIds]: a box is on when every one of them carries the label, half on when some do.
 * A tick is saved at once. From here a new label can be made and the labels managed. The rows scroll, the buttons
 * stay in view.
 */
@Composable
fun LabelPickerDialog(
    threadIds: List<Long>,
    onDismiss: () -> Unit,
    viewModel: LabelsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    var managing by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.i29_picker_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LabelItemGap)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    if (state.labels.isEmpty()) LabelDialogNote(stringResource(R.string.i29_picker_empty))
                    state.labels.forEach { label ->
                        val coverage = state.coverage(label.id, threadIds)
                        LabelCheckRow(label, coverage) {
                            viewModel.setAssigned(threadIds, label.id, coverage != LabelCoverage.All)
                        }
                    }
                }
                if (state.labels.size < LabelRules.MAX_LABELS) {
                    LabelAddButton(stringResource(R.string.i29_picker_new)) { adding = true }
                }
                if (state.labels.isNotEmpty()) {
                    TextButton(onClick = { managing = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.i29_picker_manage))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.i29_done)) } },
    )
    if (adding) {
        LabelEditDialog(
            initial = null,
            labels = state.labels,
            onSave = { name, color -> viewModel.add(name, color, threadIds) },
            onDismiss = { adding = false },
        )
    }
    if (managing) LabelManagerDialog(onDismiss = { managing = false })
}

@Composable
private fun LabelCheckRow(label: Label, coverage: LabelCoverage, onToggle: () -> Unit) {
    val checkState = when (coverage) {
        LabelCoverage.All -> ToggleableState.On
        LabelCoverage.Some -> ToggleableState.Indeterminate
        LabelCoverage.None -> ToggleableState.Off
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = LabelRowHeight)
            .triStateToggleable(state = checkState, role = Role.Checkbox, onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TriStateCheckbox(state = checkState, onClick = null)
        Spacer(Modifier.width(LabelItemGap))
        LabelDot(label.color)
        Spacer(Modifier.width(LabelItemGap))
        Text(
            label.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
