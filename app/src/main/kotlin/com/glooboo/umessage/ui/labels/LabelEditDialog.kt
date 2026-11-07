package com.glooboo.umessage.ui.labels

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.models.Label
import com.glooboo.umessage.ui.components.ICON_CONTRAST
import com.glooboo.umessage.ui.components.readableOn
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.theme.contentDirection

private const val SWATCHES_PER_ROW = 4
private val SwatchSize = 40.dp
private val SwatchRingWidth = 3.dp
private val SwatchCheckSize = 20.dp

/**
 * I29: a name and a colour for a new label ([initial] null) or for [initial]. Save is on only while the name passes
 * [LabelRules.check] against [labels]; a name that is taken, or the limit, says so under the field.
 */
@Composable
internal fun LabelEditDialog(
    initial: Label?,
    labels: List<Label>,
    onSave: (name: String, color: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var color by rememberSaveable { mutableIntStateOf(initial?.color ?: LabelRules.nextColor(labels)) }
    val verdict = LabelRules.check(labels, name, initial?.id)
    val save = {
        if (verdict == LabelResult.Ok) {
            onSave(name, color)
            onDismiss()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (initial == null) R.string.i29_edit_new_title else R.string.i29_edit_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LabelItemGap)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(LabelRules.MAX_NAME_LENGTH) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.contentDirection(),
                    label = { Text(stringResource(R.string.i29_edit_name)) },
                    isError = verdict == LabelResult.Duplicate || verdict == LabelResult.TooMany,
                    supportingText = { VerdictText(verdict) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                )
                ColorSwatches(selected = color, onPick = { color = it })
            }
        },
        confirmButton = {
            FilledTonalButton(onClick = save, enabled = verdict == LabelResult.Ok) {
                Text(stringResource(R.string.i29_edit_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.i29_cancel)) } },
    )
}

@Composable
private fun VerdictText(verdict: LabelResult) {
    when (verdict) {
        LabelResult.Duplicate -> Text(stringResource(R.string.i29_error_duplicate))
        LabelResult.TooMany -> Text(stringResource(R.string.i29_error_limit, LabelRules.MAX_LABELS))
        else -> Unit
    }
}

@Composable
private fun ColorSwatches(selected: Int, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(LabelSectionGap)) {
        LabelColor.entries.chunked(SWATCHES_PER_ROW).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(LabelSectionGap)) {
                row.forEachIndexed { index, swatch ->
                    val number = rowIndex * SWATCHES_PER_ROW + index + 1
                    val description = stringResource(R.string.i29_color_description, number)
                    Swatch(swatch.color, swatch.color == selected, description) { onPick(swatch.color) }
                }
            }
        }
    }
}

@Composable
private fun Swatch(argb: Int, on: Boolean, description: String, onClick: () -> Unit) {
    val ring = if (on) Modifier.border(SwatchRingWidth, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier
    Box(
        Modifier
            .size(LabelTarget)
            .clip(CircleShape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                contentDescription = description
                selected = on
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(SwatchSize).clip(CircleShape).background(Color(argb)).then(ring), Alignment.Center) {
            if (on) {
                Icon(
                    Symbols.Check,
                    contentDescription = null,
                    // The tick reads on a light swatch (yellow, orange) as well as on a dark one.
                    tint = readableOn(Color(argb), Color.White, ICON_CONTRAST),
                    modifier = Modifier.size(SwatchCheckSize),
                )
            }
        }
    }
}
