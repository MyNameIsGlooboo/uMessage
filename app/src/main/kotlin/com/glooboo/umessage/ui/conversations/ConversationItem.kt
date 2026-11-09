package com.glooboo.umessage.ui.conversations

import android.content.Context
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.activities.CopyCode
import com.glooboo.umessage.helpers.CodeMasker
import com.glooboo.umessage.ui.components.hideCodesSetting
import com.glooboo.umessage.ui.components.isolateNumbers
import com.glooboo.umessage.ui.components.rememberCodeHidden
import com.glooboo.umessage.ui.icons.Symbols
import kotlin.math.roundToInt

/** Row height floor: the 48dp avatar plus 12dp above and below, always above the 48dp touch target. */
private val RowMinHeight = 72.dp

/**
 * A conversation row wrapped in swipe actions (each undoable from the snackbar): [startToEnd] runs when the row is
 * swiped towards the end (right in LTR), [endToStart] towards the start; a null direction does not move. Given
 * neither, [canArchive] gives the old behaviour: both directions archive. Delete stays in selection mode and in the
 * TalkBack actions. Swiping is off while selecting; the gesture itself lives in [RowSwipeState].
 */
@Composable
fun SwipeableConversation(
    row: ConversationRow,
    selected: Boolean,
    selecting: Boolean,
    canArchive: Boolean,
    callbacks: ConversationsCallbacks,
    modifier: Modifier = Modifier,
    startToEnd: SwipeAction? = null,
    endToStart: SwipeAction? = null,
    labels: List<RowLabel> = emptyList(),
    showCodeChip: Boolean = true,
) {
    val archiveLabel = stringResource(R.string.convlist_archive)
    val archiveAction = remember(archiveLabel, callbacks) {
        SwipeAction(Symbols.Archive, archiveLabel) { callbacks.onArchiveOne(it) }
    }
    // Neither direction given: the archive swipe both ways, as before.
    val legacy = startToEnd == null && endToStart == null
    val startAction = startToEnd ?: archiveAction.takeIf { legacy && canArchive }
    val endAction = endToStart ?: archiveAction.takeIf { legacy && canArchive }
    val scope = rememberCoroutineScope()
    val swipe = remember(scope) { RowSwipeState(scope) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val flingVelocityPx = with(LocalDensity.current) { SWIPE_FLING_VELOCITY_DP.dp.toPx() }
    SideEffect {
        swipe.threadId = row.threadId
        swipe.start = startAction
        swipe.end = endAction
        swipe.selecting = selecting
        swipe.directionSign = if (rtl) -1f else 1f
        swipe.flingVelocityPx = flingVelocityPx
    }
    Box(
        modifier = modifier
            .onSizeChanged { swipe.widthPx = it.width.toFloat() }
            .pointerInput(swipe) { with(swipe) { detectSwipes() } },
    ) {
        Box(Modifier.matchParentSize()) { SwipeBackground(swipe.direction, startAction, endAction) }
        Box(Modifier.offset { IntOffset(swipe.offsetPx.roundToInt(), 0) }) {
            ConversationItem(
                row, selected, selecting, callbacks, canArchive,
                longPressEnabled = { !swipe.fingerMoved },
                swipeActions = listOfNotNull(startAction, endAction).distinct(),
                labels = labels,
                showCodeChip = showCodeChip,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationItem(
    row: ConversationRow,
    selected: Boolean,
    selecting: Boolean,
    callbacks: ConversationsCallbacks,
    canArchive: Boolean,
    modifier: Modifier = Modifier,
    longPressEnabled: () -> Boolean = { true },
    /** The row's swipe actions, mirrored as TalkBack actions. Empty: [canArchive] alone adds "Archive". */
    swipeActions: List<SwipeAction> = emptyList(),
    /** I29: the conversation's labels, shown as small chips under the preview. */
    labels: List<RowLabel> = emptyList(),
    /** U55: false leaves the code's Copy chip out (the row still has a "Copy code" action for TalkBack). */
    showCodeChip: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val background by animateColorAsState(
        targetValue = if (selected) colors.primaryContainer else colors.background,
        label = "rowBackground",
    )
    val description = rowDescription(row, labels)
    val actionLabels = RowActionLabels(
        archive = stringResource(R.string.convlist_archive),
        delete = stringResource(R.string.convlist_delete),
        copy = stringResource(R.string.convx_code_copy),
    )
    val selectLabel = stringResource(R.string.convlist_selected)
    val context = LocalContext.current
    val onClick = { if (selecting) callbacks.onToggleSelect(row.threadId) else callbacks.onOpen(row) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(background)
            .heightIn(min = RowMinHeight)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    // A finger that has started moving is scrolling or swiping, not asking for selection.
                    if (longPressEnabled()) {
                        if (selecting) callbacks.onToggleSelect(row.threadId) else callbacks.onStartSelect(row.threadId)
                    }
                },
                onLongClickLabel = selectLabel,
            )
            .semantics(mergeDescendants = true) {
                contentDescription = description
                this.selected = selected
                // Swipe gestures are not reachable with TalkBack, so mirror them as actions.
                customActions = rowAccessibilityActions(row, swipeActions, canArchive, callbacks, actionLabels, context)
            }
            // Owner note G: rows are top-aligned with a fixed 12dp top padding, so a row with a code chip starts its
            // title as far down as every other row. Centered, a two-line row got ~5dp of slack above its text from the
            // 72dp floor while a taller chip row got none. 12 + 48dp avatar + 12 is the 72dp floor itself.
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ContactAvatar(title = row.title, photoUri = row.photoUri, isGroup = row.isGroup, selected = selected)
        // A selected row sits on primaryContainer, so every text and icon role switches to its on-colour.
        val rowColors = if (selected) {
            colors.copy(
                primary = colors.onPrimaryContainer,
                onPrimary = colors.primaryContainer,
                onSurface = colors.onPrimaryContainer,
                onSurfaceVariant = colors.onPrimaryContainer,
            )
        } else {
            colors
        }
        // B3 / CL-02: the row keeps the app's layout, so the title sits next to the avatar and the date at the far end
        // whatever script the title is in. Each text reads in its own direction (an English name in the Persian UI
        // stays "Sara Ahmadi"), and title and preview both align to the avatar's edge.
        val align = if (LocalLayoutDirection.current == LayoutDirection.Rtl) TextAlign.Right else TextAlign.Left
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            RowTitleLine(row, rowColors, align)
            RowPreviewLine(row, rowColors, align)
            val code = row.otpCode
            if (code != null && showCodeChip && !selecting) {
                // The copy is the real code, marked sensitive and never cleared; the chip hides it when the user asked
                // for hidden codes.
                CodeChip(
                    code = if (hideCodesSetting()) CodeMasker.BULLETS else code,
                    onCopy = { CopyCode.copy(context, code) },
                    copyLabel = actionLabels.copy,
                )
            }
            RowLabelChips(labels, Modifier.padding(top = 4.dp))
        }
    }
}

/** Labels of the TalkBack actions of a row. */
private class RowActionLabels(val archive: String, val delete: String, val copy: String)

/** The swipe actions (else Archive), Delete and, for a fresh code, Copy code, as TalkBack custom actions. */
private fun rowAccessibilityActions(
    row: ConversationRow,
    swipeActions: List<SwipeAction>,
    canArchive: Boolean,
    callbacks: ConversationsCallbacks,
    labels: RowActionLabels,
    context: Context,
): List<CustomAccessibilityAction> = buildList {
    if (swipeActions.isNotEmpty()) {
        swipeActions.forEach { action ->
            add(CustomAccessibilityAction(action.label) { action.onCommit(row.threadId); true })
        }
    } else if (canArchive) {
        add(CustomAccessibilityAction(labels.archive) { callbacks.onArchiveOne(row.threadId); true })
    }
    add(CustomAccessibilityAction(labels.delete) { callbacks.onDeleteOne(row.threadId); true })
    row.otpCode?.let { code ->
        add(CustomAccessibilityAction(labels.copy) { CopyCode.copy(context, code); true })
    }
}

/** Group icon, title (aligned to [align], the avatar's side) and, at the far end, the date. */
@Composable
private fun RowTitleLine(row: ConversationRow, colors: ColorScheme, align: TextAlign) {
    val typography = MaterialTheme.typography
    val weight = unreadTitleWeight(row.unread)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (row.isGroup) {
                MarkerIcon(Symbols.Group, colors)
            }
            Text(
                text = isolateNumbers(row.title),
                style = typography.titleMedium.copy(textDirection = TextDirection.Content, textAlign = align),
                color = colors.onSurface,
                fontWeight = weight,
                fontStyle = if (row.scheduled) FontStyle.Italic else FontStyle.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Text(
            text = row.dateLabel,
            style = typography.labelMedium,
            color = if (row.unread) colors.primary else colors.onSurfaceVariant,
            fontWeight = weight,
            maxLines = 1,
        )
    }
}

/** Draft label and preview (aligned to [align]), then the muted, pinned, SIM and unread markers at the far end. */
@Composable
private fun RowPreviewLine(row: ConversationRow, colors: ColorScheme, align: TextAlign) {
    val typography = MaterialTheme.typography
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (row.hasDraft) {
                Text(
                    text = stringResource(R.string.convlist_draft),
                    style = typography.bodyMedium,
                    color = colors.primary,
                    fontStyle = FontStyle.Italic,
                    maxLines = 1,
                )
            }
            Text(
                // Isolates keep a number or an amount inside a Persian preview in its own order (B1).
                text = isolateNumbers(rememberCodeHidden(row.snippet)),
                style = typography.bodyMedium.copy(textDirection = TextDirection.Content, textAlign = align),
                color = if (row.unread) colors.onSurface else colors.onSurfaceVariant,
                fontWeight = unreadPreviewWeight(row.unread),
                fontStyle = if (row.scheduled) FontStyle.Italic else FontStyle.Normal,
                // One line for every preview, as in Google Messages.
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        // A private note in the chat: the same 16dp mark as muted and pinned, so the row layout stays stable.
        if (row.hasNotes) MarkerIcon(Symbols.Description, colors)
        if (row.muted) MarkerIcon(Symbols.NotificationsOff, colors)
        if (row.pinned) MarkerIcon(Symbols.KeepFilled, colors)
        row.simSlot?.let { slot ->
            SimChip(row.simName.ifEmpty { stringResource(R.string.convlist_sim, slot) })
        }
        if (row.unread) {
            UnreadBadge(row.unreadBadge)
        }
    }
}

/** CL-11: "Code 482913  Copy" under a fresh code message; one tap copies the code. */
@Composable
private fun CodeChip(code: String, onCopy: () -> Unit, copyLabel: String) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onCopy,
        shape = RoundedCornerShape(8.dp),
        color = colors.secondaryContainer,
        contentColor = colors.onSecondaryContainer,
        modifier = Modifier
            .padding(top = 4.dp)
            .heightIn(min = 32.dp)
            .semantics { role = Role.Button },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.convx_code_chip, isolateNumbers(code)),
                style = MaterialTheme.typography.labelLarge,
            )
            Icon(Symbols.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
            Text(text = copyLabel, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun rowDescription(row: ConversationRow, labels: List<RowLabel>): String {
    val parts = ArrayList<String>(8)
    parts += row.title
    if (row.unread) {
        parts += if (row.unreadCount > 0) {
            stringResource(R.string.convlist_unread_count, row.unreadCount)
        } else {
            stringResource(R.string.convlist_unread)
        }
    }
    if (row.hasDraft) parts += stringResource(R.string.convlist_draft)
    parts += row.snippet
    parts += row.dateLabel
    if (row.pinned) parts += stringResource(R.string.convlist_pinned)
    if (row.muted) parts += stringResource(R.string.convlist_muted)
    if (row.hasNotes) parts += stringResource(R.string.has_notes)
    row.simSlot?.let { parts += row.simName.ifEmpty { stringResource(R.string.convlist_sim, it) } }
    parts += labels.map { it.name }
    return parts.filter { it.isNotBlank() }.joinToString(", ")
}

/** A 16dp state icon in a row (group, muted, pinned). */
@Composable
private fun MarkerIcon(icon: ImageVector, colors: ColorScheme) {
    Icon(icon, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
}

@Composable
private fun SimChip(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/**
 * The unread count pill (or dot, for an empty [label]) at the end of a chat row; the Blocked tab draws the same one.
 */
@Composable
internal fun UnreadBadge(label: String) {
    val colors = MaterialTheme.colorScheme
    if (label.isEmpty()) {
        Badge(containerColor = colors.primary, modifier = Modifier.clearAndSetSemantics { })
        return
    }
    // M3 large-badge sizing (16dp tall, 4dp side padding), but the line box is trimmed to the font size and the
    // width never drops below the height: at large font scales it stays a circle, and "99+" grows into a pill
    // sideways instead of the M3 Badge row stretching into a tall oval.
    val style = MaterialTheme.typography.labelSmall
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clearAndSetSemantics { }
            .layout { measurable, constraints ->
                val minSide = BadgeMinSize.roundToPx()
                val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                val height = maxOf(placeable.height, minSide)
                val width = maxOf(placeable.width, height)
                layout(width, height) {
                    placeable.place((width - placeable.width) / 2, (height - placeable.height) / 2)
                }
            }
            .clip(CircleShape)
            .background(colors.primary)
            .padding(horizontal = BadgePadding, vertical = 1.dp),
    ) {
        Text(
            text = label,
            color = colors.onPrimary,
            maxLines = 1,
            style = style.copy(
                lineHeight = style.fontSize,
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            ),
        )
    }
}

private val BadgeMinSize = 16.dp
private val BadgePadding = 4.dp
