package com.glooboo.umessage.dialogs

import android.app.Activity
import androidx.compose.ui.res.stringResource
import com.glooboo.umessage.R
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.ui.components.showComposeDialog
import com.glooboo.umessage.ui.conversations.RenameConversationAlert

/**
 * Asks for a private name for a conversation. Shown as a Material dialog; the call signature is unchanged.
 * [onReset], when given, adds "Reset to default name" (CL-28) and is called when it is chosen.
 */
class RenameConversationDialog(
    activity: Activity,
    conversation: Conversation,
    onReset: (() -> Unit)? = null,
    callback: (name: String) -> Unit,
) {
    init {
        activity.showComposeDialog { dismiss ->
            RenameConversationAlert(
                title = stringResource(R.string.rename_conversation),
                initial = conversation.title,
                placeholder = conversation.title,
                hint = stringResource(R.string.rename_conversation_warning),
                onSave = {
                    callback(it)
                    dismiss()
                },
                onDismiss = dismiss,
                onReset = if (onReset != null && conversation.usesCustomTitle) {
                    {
                        onReset()
                        dismiss()
                    }
                } else {
                    null
                },
            )
        }
    }
}
