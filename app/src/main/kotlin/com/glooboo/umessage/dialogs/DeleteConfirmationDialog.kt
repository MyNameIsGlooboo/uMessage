package com.glooboo.umessage.dialogs

import android.app.Activity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.components.CheckboxRow
import com.glooboo.umessage.ui.components.ConfirmDialog
import com.glooboo.umessage.ui.components.showComposeDialog

/** Delete question with an optional "skip the Recycle Bin" choice. The call signature is unchanged. */
class DeleteConfirmationDialog(
    activity: Activity,
    message: String,
    showSkipRecycleBinOption: Boolean,
    callback: (skipRecycleBin: Boolean) -> Unit,
) {
    init {
        activity.showComposeDialog { dismiss ->
            var skip by remember { mutableStateOf(false) }
            ConfirmDialog(
                message = message,
                confirmLabel = stringResource(R.string.screens_delete),
                destructive = true,
                onConfirm = {
                    dismiss()
                    callback(skip)
                },
                onDismiss = dismiss,
                content = if (showSkipRecycleBinOption) {
                    { CheckboxRow(stringResource(R.string.screens_delete_skip_bin), skip, { skip = it }) }
                } else {
                    null
                },
            )
        }
    }
}
