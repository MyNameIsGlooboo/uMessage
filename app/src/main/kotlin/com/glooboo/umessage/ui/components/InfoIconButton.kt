package com.glooboo.umessage.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.icons.Symbols

/**
 * A small circled "i" for secondary explanations. Tapping it opens a dialog titled [title] with [text] and one Close
 * button. The touch target is 48dp; the icon itself stays small so it fits at the end of a title or switch row.
 * [extra] is optional content shown under the text.
 */
@Composable
fun InfoIconButton(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    extra: (@Composable () -> Unit)? = null,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    IconButton(
        onClick = { open = true },
        modifier = modifier.size(INFO_TARGET).testTag("info_icon"),
    ) {
        Icon(
            Symbols.Info,
            contentDescription = stringResource(R.string.p44_more_information),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(INFO_ICON),
        )
    }
    if (open) InfoTextDialog(title, text, extra) { open = false }
}

/** The dialog behind [InfoIconButton]: the row's name as title, the text, [extra] under it, and Close. */
@Composable
fun InfoTextDialog(title: String, text: String, extra: (@Composable () -> Unit)? = null, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = INFO_TEXT_MAX_HEIGHT).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(INFO_EXTRA_GAP),
            ) {
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("info_text"))
                extra?.invoke()
            }
        },
        confirmButton = {
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = INFO_TARGET)) {
                Text(stringResource(R.string.p44_close))
            }
        },
    )
}

private val INFO_TARGET = 48.dp
private val INFO_ICON = 20.dp
private val INFO_TEXT_MAX_HEIGHT = 420.dp
private val INFO_EXTRA_GAP = 16.dp
