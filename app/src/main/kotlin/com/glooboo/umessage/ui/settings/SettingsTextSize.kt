package com.glooboo.umessage.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.TextSize
import com.glooboo.umessage.ui.icons.Symbols

/** The name of a text size, shared by the slider and the pinch label. */
@Composable
internal fun TextSize.label(): String = stringResource(
    when (this) {
        TextSize.XSMALL -> R.string.w3c_text_xsmall
        TextSize.SMALL -> R.string.w3c_text_small
        TextSize.DEFAULT -> R.string.w3c_text_default
        TextSize.LARGE -> R.string.w3c_text_large
        TextSize.LARGEST -> R.string.w3c_text_largest
        TextSize.HUGE -> R.string.w3c_text_huge
        TextSize.GIANT -> R.string.w3c_text_giant
    },
)

/**
 * ID-36: "Text size" as a seven-step slider with a sample line under it. The whole app (this screen included) follows
 * the choice at once, so the sample and the rows around it show the result live. One row of its settings group.
 */
@Composable
fun TextSizeRow(current: TextSize, onChange: (TextSize) -> Unit, modifier: Modifier = Modifier) {
    val sizes = TextSize.values()
    val title = stringResource(R.string.w3c_text_size)
    val label = current.label()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(
                Symbols.TextFields,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(
            value = current.ordinal.toFloat(),
            onValueChange = { onChange(sizes[it.toInt().coerceIn(0, sizes.lastIndex)]) },
            valueRange = 0f..sizes.lastIndex.toFloat(),
            steps = sizes.size - 2,
            modifier = Modifier.semantics {
                contentDescription = title
                stateDescription = label
            },
        )
        Text(
            stringResource(R.string.w3c_text_size_sample),
            style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
