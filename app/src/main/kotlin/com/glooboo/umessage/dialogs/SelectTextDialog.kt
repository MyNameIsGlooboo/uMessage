package com.glooboo.umessage.dialogs

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.components.InfoDialog
import com.glooboo.umessage.ui.components.showComposeDialog
import com.glooboo.umessage.ui.theme.contentDirection
import org.fossify.commons.activities.BaseSimpleActivity

/** Lets the user select just a part of a message instead of copying all of it. The call signature is unchanged. */
class SelectTextDialog(val activity: BaseSimpleActivity, val text: String) {
    init {
        activity.showComposeDialog { dismiss ->
            InfoDialog(onDismiss = dismiss, title = stringResource(R.string.screens_select_text_title)) {
                // The message's own direction: a Persian text lays out right to left even in an English UI.
                SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge.contentDirection()) }
            }
        }
    }
}
