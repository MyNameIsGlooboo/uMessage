package com.glooboo.umessage.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.SimName
import com.glooboo.umessage.helpers.SimNames

/** What the user typed for one active SIM, kept while the dialog is open. */
private class SimEntry(val sim: SimCard, nickname: String, number: String) {
    var nickname by mutableStateOf(nickname)
    var number by mutableStateOf(number)
}

/**
 * I34: a nickname and a number for each active SIM. Carriers often do not give the number to apps, so it is typed here;
 * both stay on the phone. [onSave] gets the entries as typed, SIM by SIM.
 */
@Composable
internal fun SimNamesDialog(
    sims: List<SimCard>,
    names: SimNames,
    onSave: (SimCard, SimName) -> Unit,
    onDismiss: () -> Unit,
) {
    val entries = remember {
        sims.map { sim ->
            val saved = names.get(sim.slot, sim.carrier)
            SimEntry(sim, saved?.nickname.orEmpty(), saved?.number.orEmpty())
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.i34_dialog_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.i34_number_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                entries.forEach { SimEntryFields(it) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    entries.forEach { onSave(it.sim, SimName(it.nickname, it.number)) }
                    onDismiss()
                },
            ) {
                Text(stringResource(R.string.screens_ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.screens_cancel)) } },
    )
}

@Composable
private fun SimEntryFields(entry: SimEntry) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            if (entry.sim.carrier.isBlank()) {
                stringResource(R.string.thread_sim, entry.sim.slot)
            } else {
                stringResource(R.string.i34_sim_heading, entry.sim.slot, entry.sim.carrier)
            },
            style = MaterialTheme.typography.titleSmall,
        )
        OutlinedTextField(
            value = entry.nickname,
            onValueChange = { entry.nickname = it.take(SimNames.MAX_NICKNAME_LENGTH) },
            label = { Text(stringResource(R.string.i34_nickname)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = entry.number,
            onValueChange = { entry.number = it.take(SimNames.MAX_NUMBER_LENGTH) },
            label = { Text(stringResource(R.string.i34_number)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
