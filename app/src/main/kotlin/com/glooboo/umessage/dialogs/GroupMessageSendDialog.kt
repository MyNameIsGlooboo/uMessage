package com.glooboo.umessage.dialogs

import android.app.Activity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.components.showComposeDialog

/**
 * Asks whether a group message goes out as one group MMS or as separate messages. [onDismiss] runs on any close,
 * then [callback] with the choice if one was made. The call signature is unchanged.
 */
class GroupMessageSendDialog(
    activity: Activity,
    callback: (sendAsGroupMms: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    init {
        activity.showComposeDialog { dismiss ->
            val finish = { choice: Boolean? ->
                dismiss()
                onDismiss()
                choice?.let(callback)
                Unit
            }
            AlertDialog(
                onDismissRequest = { finish(null) },
                title = { Text(stringResource(R.string.send_group_message)) },
                text = { Text(stringResource(R.string.group_message_send_description), style = MaterialTheme.typography.bodyMedium) },
                confirmButton = { TextButton(onClick = { finish(true) }) { Text(stringResource(R.string.group_mms)) } },
                dismissButton = { TextButton(onClick = { finish(false) }) { Text(stringResource(R.string.separate_messages)) } },
            )
        }
    }
}
