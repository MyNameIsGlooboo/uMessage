package com.glooboo.umessage.ui.labels

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.models.Label
import com.glooboo.umessage.ui.icons.Symbols
import com.glooboo.umessage.ui.main.InboxFilter
import com.glooboo.umessage.ui.main.MainSearchState

private val ChipHeight = 32.dp
private val ChipGap = 8.dp
private val ChipPadding = 12.dp
private val ChipDotSize = 12.dp
private val ChipDotGap = 8.dp
private val ChipBorderWidth = 1.dp

/** What a tap and the two items of the long-press menu do with the label of the given id. */
internal class LabelChipActions(val onToggle: (Long) -> Unit, val onEdit: (Long) -> Unit, val onDelete: (Long) -> Unit)

/**
 * The user's labels as wrapping chips, a colour dot and the name on each. A tap filters by the label ([selectedId] is
 * the one that is on); a long press, or the "Label options" action in TalkBack, opens Edit and Delete.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchLabelChips(
    labels: List<Label>,
    selectedId: Long?,
    actions: LabelChipActions,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(ChipGap),
        verticalArrangement = Arrangement.spacedBy(ChipGap),
    ) {
        labels.forEach { label -> LabelChip(label, label.id == selectedId, actions) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LabelChip(label: Label, selected: Boolean, actions: LabelChipActions) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    val optionsLabel = stringResource(R.string.search_labels_chip_options)
    Box {
        Row(
            Modifier
                .height(ChipHeight)
                .clip(CircleShape)
                .background(if (selected) colors.secondaryContainer else colors.surfaceContainerHigh)
                .border(ChipBorderWidth, if (selected) colors.primary else colors.outlineVariant, CircleShape)
                .combinedClickable(
                    role = Role.Button,
                    onClick = { actions.onToggle(label.id) },
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    },
                )
                .semantics {
                    this.selected = selected
                    customActions = listOf(
                        CustomAccessibilityAction(optionsLabel) {
                            menuOpen = true
                            true
                        },
                    )
                }
                .padding(horizontal = ChipPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LabelDot(label.color, Modifier.size(ChipDotSize))
            Spacer(Modifier.width(ChipDotGap))
            Text(
                label.name,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) colors.onSecondaryContainer else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        LabelChipMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            onEdit = {
                menuOpen = false
                actions.onEdit(label.id)
            },
            onDelete = {
                menuOpen = false
                actions.onDelete(label.id)
            },
        )
    }
}

@Composable
private fun LabelChipMenu(expanded: Boolean, onDismiss: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.search_labels_chip_edit)) },
            leadingIcon = { Icon(Symbols.Edit, contentDescription = null) },
            onClick = onEdit,
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.search_labels_chip_delete)) },
            leadingIcon = { Icon(Symbols.Delete, contentDescription = null) },
            onClick = onDelete,
        )
    }
}

/** The id of the label whose filter is on, else null. */
internal val MainSearchState.selectedLabelId: Long?
    get() = if (filter == InboxFilter.Label) labelId else null

/** Turns the filter of label [labelId] on, or off when it is the one that is on. */
internal fun MainSearchState.toggleLabel(labelId: Long) {
    if (filter == InboxFilter.Label && this.labelId == labelId) {
        filter = InboxFilter.All
    } else {
        this.labelId = labelId
        filter = InboxFilter.Label
    }
}

/** A deleted label cannot stay the filter: back to everything when it was the one that was on. */
internal fun MainSearchState.forgetLabel(labelId: Long) {
    if (filter == InboxFilter.Label && this.labelId == labelId) filter = InboxFilter.All
}
