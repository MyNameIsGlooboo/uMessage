package com.glooboo.umessage.dialogs

import android.app.Activity
import androidx.compose.ui.res.stringResource
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.components.ConfirmDialog
import com.glooboo.umessage.ui.components.showComposeDialog

/**
 * Warns before messages are shared as text (I38): how many go out and from when to when, and that they leave the app.
 * [summary] is the count and date range; the chooser that follows names the app they go to.
 */
class ShareTextDialog(activity: Activity, summary: String, onShare: () -> Unit) {
    init {
        activity.showComposeDialog { dismiss ->
            ConfirmDialog(
                title = stringResource(R.string.share_text_title),
                message = summary + "\n\n" + stringResource(R.string.share_text_warning),
                confirmLabel = stringResource(R.string.share_text_action),
                onConfirm = {
                    dismiss()
                    onShare()
                },
                onDismiss = dismiss,
            )
        }
    }
}
