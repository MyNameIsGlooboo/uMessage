package com.glooboo.umessage.dialogs

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.glooboo.umessage.ui.components.InfoDialog
import com.glooboo.umessage.ui.components.showComposeDialog
import org.fossify.commons.activities.BaseSimpleActivity

/** Explains why a recipient number cannot be used. The call signature is unchanged. */
class InvalidNumberDialog(val activity: BaseSimpleActivity, val text: String) {
    init {
        activity.showComposeDialog { dismiss ->
            InfoDialog(onDismiss = dismiss) {
                SelectionContainer { Text(text, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}
