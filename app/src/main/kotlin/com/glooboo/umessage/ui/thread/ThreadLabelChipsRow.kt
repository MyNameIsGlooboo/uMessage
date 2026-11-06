package com.glooboo.umessage.ui.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.SubcomposeMeasureScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glooboo.umessage.R
import com.glooboo.umessage.models.Label
import com.glooboo.umessage.ui.labels.LabelsViewModel

private val ChipNameMaxWidth = 72.dp
private val ChipDotSize = 8.dp
private val ChipInnerGap = 4.dp
private val ChipCorner = 8.dp
private val ChipPaddingHorizontal = 8.dp
private val ChipPaddingVertical = 3.dp
private val MetaGap = 6.dp
private const val CHIP_FILL_ALPHA = 0.2f
private const val LINE_GAP_DIVISOR = 2

private class MetaSlots(
    val subtitle: (@Composable () -> Unit)?,
    val status: (@Composable () -> Unit)?,
    val chips: ThreadLabelChips,
    val description: String,
    val onClick: () -> Unit,
)

private class ChipPlan(val parts: List<Placeable>, val wrapped: Boolean)

/**
 * The second line of the conversation title: the [subtitle], the sender [status] chip and the labels of the
 * conversation (I29) as chips of the same size. When the labels do not all fit beside the rest they shrink to the
 * first ones and "+n", and only when not even "+n" fits do they move to a line of their own. A tap on the labels
 * opens the label picker ([onLabelsClick]). Nothing shows for a part that has no content.
 */
@Composable
internal fun ThreadMetaRow(
    threadId: Long,
    subtitle: (@Composable () -> Unit)?,
    status: (@Composable () -> Unit)?,
    onLabelsClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LabelsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val chips = remember(state, threadId) { ThreadLabelChipRules.chipsFor(state, threadId) }
    val description = stringResource(R.string.thread_labels_chips_description, chips.shown.joinToString { it.name })
    val gap = LocalDensity.current.run { MetaGap.roundToPx() }
    val slots = MetaSlots(subtitle, status, chips, description, onLabelsClick)
    SubcomposeLayout(modifier) { constraints -> measureMeta(constraints, gap, slots) }
}

private fun SubcomposeMeasureScope.measureMeta(constraints: Constraints, gap: Int, slots: MetaSlots): MeasureResult {
    val width = constraints.maxWidth
    val status = slots.status?.let { measureSlot("status", width, it) }
    val statusSpace = status?.let { it.width + gap } ?: 0
    val subtitle = slots.subtitle?.let { measureSlot("subtitle", (width - statusSpace).coerceAtLeast(0), it) }
    val items = listOfNotNull(subtitle, status)
    val itemsWidth = items.sumOf { it.width } + gap * (items.size - 1).coerceAtLeast(0)
    val itemsHeight = items.maxOfOrNull { it.height } ?: 0
    val beside = if (items.isEmpty()) 0 else gap
    val plan = planChips(slots.chips, width, gap, width - itemsWidth - beside)
    val groupWidth = plan.parts.sumOf { it.width } + gap * (plan.parts.size - 1).coerceAtLeast(0)
    val groupHeight = plan.parts.maxOfOrNull { it.height } ?: 0
    val rowHeight = if (plan.wrapped) itemsHeight else maxOf(itemsHeight, groupHeight)
    val groupX = if (plan.wrapped) 0 else itemsWidth + beside
    val groupY = if (plan.wrapped) rowHeight + gap / LINE_GAP_DIVISOR else (rowHeight - groupHeight) / 2
    val totalWidth = maxOf(itemsWidth, groupX + groupWidth).coerceAtMost(width)
    val totalHeight = if (plan.wrapped && groupHeight > 0) groupY + groupHeight else rowHeight
    val hit = measureHit(slots, plan, groupWidth, groupHeight)
    return layout(totalWidth, totalHeight) {
        placeRow(items, rowHeight, gap)
        hit?.placeRelative(groupX, groupY)
        placeRow(plan.parts, groupHeight, gap, groupX, groupY)
    }
}

private fun SubcomposeMeasureScope.measureHit(slots: MetaSlots, plan: ChipPlan, width: Int, height: Int): Placeable? =
    if (plan.parts.isEmpty()) {
        null
    } else {
        subcompose("hit") { LabelsHitArea(slots) }.first().measure(Constraints.fixed(width, height))
    }

/** Places [parts] left to right from ([startX], [top]), each centred in a line [lineHeight] high. */
private fun Placeable.PlacementScope.placeRow(
    parts: List<Placeable>,
    lineHeight: Int,
    gap: Int,
    startX: Int = 0,
    top: Int = 0,
) {
    var x = startX
    parts.forEach {
        it.placeRelative(x, top + (lineHeight - it.height) / 2)
        x += it.width + gap
    }
}

private fun SubcomposeMeasureScope.planChips(chips: ThreadLabelChips, width: Int, gap: Int, room: Int): ChipPlan {
    val chipItems = chips.shown.map { label -> measureSlot(label.id, width) { LabelChip(label) } }
    val more = HashMap<Int, Placeable>()
    fun moreFor(count: Int) = more.getOrPut(count) { measureSlot("more$count", width) { MoreChip(count) } }
    val widths = chipItems.map { it.width }
    val beside = ThreadLabelChipRules.fitCount(widths, chips.hidden, room, gap) { moreFor(it).width }
    val count = beside ?: ThreadLabelChipRules.fitCount(widths, chips.hidden, width, gap) { moreFor(it).width }
    if (count == null) return ChipPlan(emptyList(), beside == null)
    val remaining = chipItems.size - count + chips.hidden
    val parts = chipItems.take(count) + listOfNotNull(if (remaining > 0) moreFor(remaining) else null)
    return ChipPlan(parts, beside == null)
}

private fun SubcomposeMeasureScope.measureSlot(key: Any, maxWidth: Int, content: @Composable () -> Unit): Placeable =
    subcompose(key, content).first().measure(Constraints(maxWidth = maxWidth))

/** Sits behind the chips: one tap target and one spoken description for all of them. */
@Composable
private fun LabelsHitArea(slots: MetaSlots) {
    Box(
        Modifier
            .clip(RoundedCornerShape(ChipCorner))
            .clickable(role = Role.Button, onClick = slots.onClick)
            .semantics { contentDescription = slots.description },
    )
}

/** The look all the chips of this line share, the sender status chip included. */
@Composable
private fun ChipShell(container: Color, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(ChipCorner))
            .background(container)
            .padding(horizontal = ChipPaddingHorizontal, vertical = ChipPaddingVertical)
            .clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(ChipInnerGap),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun LabelChip(label: Label) {
    val tint = Color(label.color)
    ChipShell(tint.copy(alpha = CHIP_FILL_ALPHA)) {
        Box(Modifier.size(ChipDotSize).clip(CircleShape).background(tint))
        Text(
            label.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = ChipNameMaxWidth),
        )
    }
}

@Composable
private fun MoreChip(count: Int) {
    ChipShell(MaterialTheme.colorScheme.surfaceVariant) {
        Text(
            stringResource(R.string.thread_labels_more_chip, count),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
