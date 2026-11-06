package com.glooboo.umessage.ui.settings

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.MessagesWriter
import com.glooboo.umessage.helpers.SimImportSummary
import com.glooboo.umessage.helpers.SimMessage
import com.glooboo.umessage.helpers.SimMessagesImporter
import com.glooboo.umessage.helpers.SimMessagesReader
import com.glooboo.umessage.helpers.SimReadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface SimDialogState {
    data object Loading : SimDialogState
    data object Unavailable : SimDialogState
    data object Empty : SimDialogState
    data class Found(val messages: List<SimMessage>) : SimDialogState
    data object Importing : SimDialogState
    data class Done(val summary: SimImportSummary) : SimDialogState
}

private suspend fun readSim(context: Context): SimDialogState = withContext(Dispatchers.IO) {
    val reader = SimMessagesReader(context.contentResolver) { SimCards.load(context).map { it.subscriptionId } }
    when (val result = reader.read()) {
        SimReadResult.Unavailable -> SimDialogState.Unavailable
        is SimReadResult.Messages ->
            if (result.messages.isEmpty()) SimDialogState.Empty else SimDialogState.Found(result.messages)
    }
}

private suspend fun importSim(context: Context, messages: List<SimMessage>): SimImportSummary =
    withContext(Dispatchers.IO) {
        val writer = MessagesWriter(context)
        SimMessagesImporter(writer::writeSmsMessage).import(messages).also { writer.fixConversationDates() }
    }

/**
 * I62: tells how many texts the SIM card holds and copies them into the conversations after one confirm. Nothing is
 * deleted from the SIM.
 */
@Composable
internal fun SimMessagesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<SimDialogState>(SimDialogState.Loading) }
    LaunchedEffect(Unit) { state = readSim(context) }
    val current = state
    val found = current as? SimDialogState.Found
    AlertDialog(
        onDismissRequest = { if (current != SimDialogState.Importing) onDismiss() },
        title = { Text(stringResource(R.string.sim_messages_title)) },
        text = { Text(simMessagesText(current)) },
        confirmButton = {
            if (found != null) {
                TextButton(onClick = {
                    state = SimDialogState.Importing
                    scope.launch { state = SimDialogState.Done(importSim(context, found.messages)) }
                }) { Text(stringResource(R.string.sim_messages_import)) }
            } else if (current != SimDialogState.Loading && current != SimDialogState.Importing) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.screens_ok)) }
            }
        },
        dismissButton = {
            if (found != null) TextButton(onClick = onDismiss) { Text(stringResource(R.string.screens_cancel)) }
        },
    )
}

@Composable
private fun simMessagesText(state: SimDialogState): String = when (state) {
    SimDialogState.Loading -> stringResource(R.string.sim_messages_loading)
    SimDialogState.Unavailable -> stringResource(R.string.sim_messages_unavailable)
    SimDialogState.Empty -> stringResource(R.string.sim_messages_empty)
    is SimDialogState.Found ->
        pluralStringResource(R.plurals.sim_messages_found, state.messages.size, state.messages.size)
    SimDialogState.Importing -> stringResource(R.string.sim_messages_importing)
    is SimDialogState.Done -> stringResource(
        if (state.summary.failed == 0) R.string.sim_messages_done else R.string.sim_messages_done_failed,
        state.summary.added, state.summary.duplicates, state.summary.failed,
    )
}
