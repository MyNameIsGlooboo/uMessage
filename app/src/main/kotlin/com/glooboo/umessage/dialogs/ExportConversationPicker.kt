package com.glooboo.umessage.dialogs

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.export.ExportScope
import com.glooboo.umessage.extensions.getConversations
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.ui.components.CheckboxRow
import com.glooboo.umessage.ui.components.showComposeDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A list of the phone's conversations with a search box and a checkbox on each, for choosing what to export.
 * [onDone] gets the chosen thread ids, in the list's order; an empty answer means the user chose nothing or cancelled.
 */
class ExportConversationPicker(activity: Activity, initial: List<Long>, onDone: (List<Long>) -> Unit) {
    init {
        activity.showComposeDialog { dismiss ->
            PickerDialog(
                initial = initial,
                onConfirm = {
                    dismiss()
                    onDone(it)
                },
                onCancel = {
                    dismiss()
                    onDone(emptyList())
                },
            )
        }
    }
}

@Composable
private fun PickerDialog(initial: List<Long>, onConfirm: (List<Long>) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    var conversations by remember { mutableStateOf<List<Conversation>?>(null) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(initial.toSet()) }
    LaunchedEffect(Unit) {
        conversations = withContext(Dispatchers.IO) {
            runCatching {
                val hidden = ExportScope.hiddenIds(context)
                context.getConversations().filterNot { it.threadId in hidden }
            }.getOrDefault(emptyList())
        }
    }
    val all = conversations
    val shown = all?.filter { query.isBlank() || it.matches(query.trim()) }.orEmpty()
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.export_pick_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.export_pick_search)) },
                    singleLine = true,
                )
                PickerBody(all, shown, selected, { selected = it })
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(all.orEmpty().map { it.threadId }.filter { it in selected }) },
                enabled = selected.isNotEmpty(),
            ) { Text(stringResource(R.string.screens_ok)) }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.screens_cancel)) } },
    )
}

@Composable
private fun PickerBody(
    all: List<Conversation>?,
    shown: List<Conversation>,
    selected: Set<Long>,
    onSelectedChange: (Set<Long>) -> Unit,
) {
    Column {
        when {
            all == null -> CircularProgressIndicator(Modifier.padding(top = 24.dp))
            all.isEmpty() -> Text(
                text = stringResource(R.string.export_pick_empty),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp),
            )

            else -> {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onSelectedChange(selected + shown.map { it.threadId }) }) {
                        Text(stringResource(R.string.export_pick_all))
                    }
                    TextButton(onClick = { onSelectedChange(selected - shown.map { it.threadId }.toSet()) }) {
                        Text(stringResource(R.string.export_pick_none))
                    }
                }
                LazyColumn(Modifier.heightIn(max = LIST_MAX_HEIGHT.dp)) {
                    items(shown, key = { it.threadId }) { conversation ->
                        CheckboxRow(
                            label = conversation.title.ifBlank { conversation.phoneNumber },
                            checked = conversation.threadId in selected,
                            onCheckedChange = {
                                onSelectedChange(
                                    if (it) selected + conversation.threadId else selected - conversation.threadId
                                )
                            },
                            supporting = conversation.phoneNumber.takeIf {
                                it.isNotBlank() && it != conversation.title
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun Conversation.matches(query: String) =
    title.contains(query, ignoreCase = true) || phoneNumber.contains(query, ignoreCase = true)

private const val LIST_MAX_HEIGHT = 320
