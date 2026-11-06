package com.glooboo.umessage.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.heading
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import com.glooboo.umessage.ui.icons.Symbols

/**
 * A titled, flat group of settings rows: primary-coloured header above rows on a tonal background.
 * The first and last row have 28dp outer corners, the others 4dp, with a 2dp gap between rows.
 * Every direct child of [content] is one row (emit nothing else, e.g. no dividers).
 */
@Composable
fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = if (description == null) 8.dp else 2.dp)
                .semantics { heading() },
        )
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
        }
        TonalRows(modifier = Modifier.padding(horizontal = 16.dp), content = content)
    }
}

private class RowSlot(val y: Int, val height: Int, val shape: Shape)

/** Stacks its children with a gap, clips each to its [GroupShape] corners and paints the tonal background. */
@Composable
fun TonalRows(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // SE-22: in a dark scheme the rows sit one tonal step higher, so they separate from the page.
    val scheme = MaterialTheme.colorScheme
    val container = if (scheme.background.luminance() < 0.2f) scheme.surfaceContainerHigh else scheme.surfaceContainer
    var slots by remember { mutableStateOf(emptyList<RowSlot>()) }
    val last = remember { arrayOfNulls<List<RowSlot>>(1) }
    Layout(
        content = content,
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                slots.forEach { slot ->
                    val outline = slot.shape.createOutline(Size(size.width, slot.height.toFloat()), layoutDirection, this)
                    translate(top = slot.y.toFloat()) {
                        val path = Path()
                        when (outline) {
                            is Outline.Rounded -> path.addRoundRect(outline.roundRect)
                            is Outline.Rectangle -> path.addRect(outline.rect)
                            is Outline.Generic -> path.addPath(outline.path)
                        }
                        drawPath(path, container)
                    }
                }
            },
    ) { measurables, constraints ->
        val gap = GroupShape.GAP_DP.dp.roundToPx()
        val childConstraints = constraints.copy(minHeight = 0)
        val placeables = measurables.map { it.measure(childConstraints) }
        val count = placeables.size
        var y = 0
        val next = ArrayList<RowSlot>(count)
        placeables.forEachIndexed { i, p ->
            val c = GroupShape.corners(i, count)
            val shape = RoundedCornerShape(
                topStart = c.topDp.dp, topEnd = c.topDp.dp, bottomEnd = c.bottomDp.dp, bottomStart = c.bottomDp.dp,
            )
            next += RowSlot(y, p.height, shape)
            y += p.height + if (i < count - 1) gap else 0
        }
        val width = placeables.maxOfOrNull { it.width } ?: constraints.minWidth
        layout(maxOf(width, constraints.minWidth), y) {
            placeables.forEachIndexed { i, p ->
                val slot = next[i]
                p.placeRelativeWithLayer(0, slot.y) {
                    shape = slot.shape
                    clip = true
                }
            }
            val prev = last[0]
            if (prev == null || prev.size != next.size || prev.indices.any { prev[it].y != next[it].y || prev[it].height != next[it].height }) {
                last[0] = next
                slots = next
            }
        }
    }
}

@Composable
private fun RowIconSlot(icon: ImageVector?) {
    if (icon != null) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp),
        )
    }
}

/**
 * Settings row with optional icon, summary and trailing content. With [onClick] the whole row is a 48dp+
 * button target; without it the row is read as one static item.
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    showChevron: Boolean = false,
    badge: String? = null,
    /** Current value shown at the end of the row (dialog rows), before the chevron. */
    value: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    val base = modifier
        .fillMaxWidth()
        .heightIn(min = 64.dp)
    val interactive = if (onClick != null) {
        base.clickable(role = Role.Button, onClick = onClick)
    } else {
        base.semantics(mergeDescendants = true) { }
    }
    Row(
        modifier = interactive.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RowIconSlot(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) {
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (badge != null) {
                InfoChip(text = badge, modifier = Modifier.padding(top = 4.dp))
            }
        }
        if (value != null) {
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
        if (showChevron) {
            Icon(
                imageVector = Symbols.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Whole-row switch with optional icon and summary; the Switch is not separately focusable. */
@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: ImageVector? = null,
    badge: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        RowIconSlot(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) {
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (badge != null) {
                InfoChip(text = badge, modifier = Modifier.padding(top = 4.dp))
            }
        }
        // VS-27, BR-43, U08: a calm outlined off state and a check when on, so off reads as off, not as an error.
        UMessageSwitch(checked = checked, onCheckedChange = null)
    }
}

/** Tint of the chosen radio row over its group's background. */
private const val SELECTED_ROW_ALPHA = 0.45f

/** Whole-row single-choice option: title, summary and a radio button; the row is the touch target. */
@Composable
fun SettingsRadioRow(
    title: String,
    summary: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    /** Small chip next to the title, e.g. "Recommended". */
    badge: String? = null,
) {
    val selectedTint = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = SELECTED_ROW_ALPHA)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            // SE-24: the chosen option is tinted, not only marked by the radio ring.
            .then(if (selected) Modifier.background(selectedTint) else Modifier)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (badge != null) InfoChip(text = badge)
            }
            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RadioButton(selected = selected, onClick = null)
    }
}
