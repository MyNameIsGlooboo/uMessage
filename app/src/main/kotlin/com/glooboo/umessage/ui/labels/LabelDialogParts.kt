package com.glooboo.umessage.ui.labels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.ui.icons.Symbols

/** The spacing the three label dialogs (pick, manage, edit) share. */
internal val LabelTarget = 48.dp
internal val LabelRowHeight = 56.dp
internal val LabelDotSize = 20.dp
internal val LabelItemGap = 16.dp
internal val LabelSectionGap = 8.dp
private val AddIconSize = 18.dp
private val AddIconGap = 8.dp

/** A filled circle in the colour of a label. */
@Composable
internal fun LabelDot(argb: Int, modifier: Modifier = Modifier) {
    Box(modifier.size(LabelDotSize).clip(CircleShape).background(Color(argb)))
}

/** The full-width tonal button with a plus that makes a new label, at the bottom of the pick and manage dialogs. */
@Composable
internal fun LabelAddButton(text: String, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = LabelTarget)) {
        Icon(Symbols.Add, contentDescription = null, modifier = Modifier.size(AddIconSize))
        Spacer(Modifier.width(AddIconGap))
        Text(text)
    }
}

/** A short line of secondary text, for a note or an empty list. */
@Composable
internal fun LabelDialogNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
