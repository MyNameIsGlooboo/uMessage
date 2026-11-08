package com.glooboo.umessage.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.ui.icons.Symbols

/**
 * The action row of an edit dialog with a destructive Delete: Delete (red, with a bin) at the start edge, which
 * mirrors in right-to-left languages, and Cancel and Save together at the end. Pass it as the dialog's
 * `confirmButton` and leave `dismissButton` empty. [onDelete] is null when the item is new, so Delete is not drawn.
 */
@Composable
fun DeleteStartActions(
    deleteLabel: String,
    onDelete: (() -> Unit)?,
    cancelLabel: String,
    onCancel: () -> Unit,
    saveLabel: String,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    saveEnabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (onDelete != null) {
            TextButton(
                onClick = onDelete,
                modifier = Modifier.heightIn(min = 48.dp).testTag("dialog_delete"),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Symbols.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(deleteLabel, modifier = Modifier.padding(start = 8.dp))
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp).testTag("dialog_cancel")) {
            Text(cancelLabel)
        }
        TextButton(
            onClick = onSave,
            enabled = saveEnabled,
            modifier = Modifier.heightIn(min = 48.dp).testTag("dialog_save"),
        ) { Text(saveLabel) }
    }
}
