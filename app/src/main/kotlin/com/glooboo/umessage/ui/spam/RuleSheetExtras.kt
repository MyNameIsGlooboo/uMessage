package com.glooboo.umessage.ui.spam

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.ListKind

/** "When it matches": always allow or always block, the one choice after what to match. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuleKindChoice(kind: ListKind, onKind: (ListKind) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.ux8d_action_label), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RuleChoiceChip(
                selected = kind == ListKind.ALLOW,
                onClick = { onKind(ListKind.ALLOW) },
                label = stringResource(R.string.rules2_sheet_list_allow),
            )
            RuleChoiceChip(
                selected = kind == ListKind.BLOCK,
                onClick = { onKind(ListKind.BLOCK) },
                label = stringResource(R.string.rules2_sheet_list_block),
            )
        }
    }
}

/**
 * The options most rules never need, behind "More options": which SIM (dual-SIM phones only) and a note. They are open
 * from the start when the rule already has a SIM or a note.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuleSheetExtras(form: RuleForm, sims: List<SimInfo>, actions: RulesActions, modifier: Modifier = Modifier) {
    val used = form.note.isNotBlank() || form.subscriptionId != null || form.editing?.note?.isNotBlank() == true
    var open by rememberSaveable { mutableStateOf(used) }
    if (!open) {
        TextButton(onClick = { open = true }, modifier = modifier) { Text(stringResource(R.string.ux8d_more_options)) }
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (sims.size >= 2) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.spam_sheet_sim_label), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuleChoiceChip(
                        selected = form.subscriptionId == null,
                        onClick = { actions.onSim(null) },
                        label = stringResource(R.string.spam_sim_both),
                    )
                    sims.forEach { sim ->
                        val label = if (sim.carrier.isBlank()) {
                            stringResource(R.string.spam_sim_label, sim.slot)
                        } else {
                            stringResource(R.string.spam_sim_named, sim.slot, sim.carrier)
                        }
                        RuleChoiceChip(
                            selected = form.subscriptionId == sim.subscriptionId,
                            onClick = { actions.onSim(sim.subscriptionId) },
                            label = label,
                        )
                    }
                }
                Text(
                    stringResource(R.string.rules2_sheet_sim_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        OutlinedTextField(
            value = form.note,
            onValueChange = actions.onNote,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.spam_sheet_note_label)) },
            // A note the app wrote stays unless the user types their own; it shows as the placeholder.
            placeholder = form.editing?.note?.let(RuleNotes::codeOf)?.let(RuleNotes::textRes)?.let { res ->
                { Text(stringResource(res)) }
            },
            singleLine = true,
        )
    }
}
