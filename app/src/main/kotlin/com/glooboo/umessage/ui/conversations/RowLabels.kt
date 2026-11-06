package com.glooboo.umessage.ui.conversations

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.labels.LabelsUiState
import com.glooboo.umessage.ui.labels.LabelsViewModel
import kotlin.math.pow

/** A label of a conversation as the rows show it: its name and colour (ARGB). */
@Immutable
data class RowLabel(val name: String, val color: Int)

/** The chips a row shows: at most [MAX_ROW_LABELS] labels, then "+[more]" for the rest. */
@Immutable
internal class LabelChips(val shown: List<RowLabel>, val more: Int)

internal const val MAX_ROW_LABELS = 2

internal fun labelChips(labels: List<RowLabel>, max: Int = MAX_ROW_LABELS): LabelChips =
    LabelChips(labels.take(max), (labels.size - max).coerceAtLeast(0))

/** Each thread's labels, in the order the user keeps the labels. */
internal fun labelsByThread(state: LabelsUiState): Map<Long, List<RowLabel>> {
    val byThread = HashMap<Long, MutableList<RowLabel>>()
    state.labels.forEach { label ->
        state.threadsOf(label.id).forEach { thread ->
            byThread.getOrPut(thread) { ArrayList() }.add(RowLabel(label.name, label.color))
        }
    }
    return byThread
}

private const val OPAQUE = -0x1000000
private const val WHITE = 0xFFFFFF
private const val BLACK = 0x000000
private const val CHANNEL_MAX = 255.0
private const val LINEAR_CUTOFF = 0.03928
private const val LINEAR_DIVISOR = 12.92
private const val GAMMA_OFFSET = 0.055
private const val GAMMA_DIVISOR = 1.055
private const val GAMMA = 2.4
private const val RED_WEIGHT = 0.2126
private const val GREEN_WEIGHT = 0.7152
private const val BLUE_WEIGHT = 0.0722
private const val CONTRAST_FLARE = 0.05
private const val SHIFT_RED = 16
private const val SHIFT_GREEN = 8
private const val CHANNEL_MASK = 0xFF

private fun linear(channel: Int): Double {
    val v = channel / CHANNEL_MAX
    return if (v <= LINEAR_CUTOFF) v / LINEAR_DIVISOR else ((v + GAMMA_OFFSET) / GAMMA_DIVISOR).pow(GAMMA)
}

private fun luminance(argb: Int): Double =
    RED_WEIGHT * linear((argb shr SHIFT_RED) and CHANNEL_MASK) +
        GREEN_WEIGHT * linear((argb shr SHIFT_GREEN) and CHANNEL_MASK) +
        BLUE_WEIGHT * linear(argb and CHANNEL_MASK)

/** WCAG contrast ratio of two colours (alpha is ignored). */
internal fun contrastRatio(first: Int, second: Int): Double {
    val a = luminance(first)
    val b = luminance(second)
    return (maxOf(a, b) + CONTRAST_FLARE) / (minOf(a, b) + CONTRAST_FLARE)
}

/** White or black text, whichever reads better on a chip of colour [background]. */
internal fun readableOn(background: Int): Int =
    if (contrastRatio(background, WHITE) >= contrastRatio(background, BLACK)) WHITE else BLACK

/** The labels of every conversation, kept up to date from the labels database. */
@Composable
internal fun rememberRowLabels(): Map<Long, List<RowLabel>> {
    val state by viewModel<LabelsViewModel>().state.collectAsStateWithLifecycle()
    return remember(state) { labelsByThread(state) }
}

/** The small colour chips of a row or search result: two labels at most, then "+n". Nothing without labels. */
@Composable
fun RowLabelChips(labels: List<RowLabel>, modifier: Modifier = Modifier) {
    if (labels.isEmpty()) return
    val chips = labelChips(labels)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        chips.shown.forEach { label ->
            LabelChip(label.name, Color(label.color), Color(OPAQUE or readableOn(label.color)))
        }
        if (chips.more > 0) {
            val colors = MaterialTheme.colorScheme
            val more = stringResource(R.string.search_page_labels_more, chips.more)
            LabelChip(more, colors.surfaceContainerHigh, colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun LabelChip(text: String, background: Color, content: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = content,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}
