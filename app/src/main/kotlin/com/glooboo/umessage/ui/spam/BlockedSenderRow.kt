package com.glooboo.umessage.ui.spam

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.ui.components.SenderAvatar
import com.glooboo.umessage.ui.components.displayNumber
import com.glooboo.umessage.ui.components.isolateNumbers
import com.glooboo.umessage.ui.components.localizedNumber
import com.glooboo.umessage.ui.components.rememberTimeLabel
import com.glooboo.umessage.ui.components.uiLocale
import com.glooboo.umessage.ui.conversations.SwipeAction
import com.glooboo.umessage.ui.conversations.SwipeBackground
import com.glooboo.umessage.ui.conversations.MAX_UNREAD_BADGE
import com.glooboo.umessage.ui.conversations.UnreadBadge
import com.glooboo.umessage.ui.conversations.unreadPreviewWeight
import com.glooboo.umessage.ui.conversations.unreadTitleWeight
import com.glooboo.umessage.ui.components.localizeDigits
import com.glooboo.umessage.ui.icons.Symbols

/** Row height floor, as on the Messages tab: two lines of text plus padding. */
private val RowMinHeight = 72.dp

/**
 * One sender on the Blocked tab, drawn like a Messages tab row (owner notes r3, round 4): avatar, sender and time, then
 * the latest text on one ellipsized line with the SIM and message count at the end. No reason line: why it was held
 * is in the filter chips and the thread's "Why blocked"; only "May be important" shows, as a chip by the time. TalkBack
 * still reads the reason. Tap opens the sender's thread; long press selects. Swiping towards the end moves the sender
 * to the inbox (BR-02), with the same Undo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BlockedSenderRow(
    group: SenderGroup,
    sims: List<SimInfo>,
    rules: Map<Long, SpamRule>,
    selected: Boolean,
    selecting: Boolean,
    actions: BlockedActions,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val single = setOf(group.address)
    val swipeState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd) actions.onNotSpam(single)
            false
        },
    )
    val background by animateColorAsState(
        targetValue = if (selected) colors.primaryContainer else colors.background,
        animationSpec = if (reduceMotion) snap() else tween(150),
        label = "blockedRowBackground",
    )
    // The same icon-plus-label background the Messages and Archived rows draw; it follows the layout direction.
    val swipeLabel = stringResource(R.string.blocked_action_move_inbox)
    val notSpamSwipe = remember(swipeLabel) { SwipeAction(Symbols.Inbox, swipeLabel) {} }
    SwipeToDismissBox(
        state = swipeState,
        modifier = modifier,
        enableDismissFromStartToEnd = !selecting,
        enableDismissFromEndToStart = false,
        backgroundContent = { SwipeBackground(swipeState.dismissDirection, notSpamSwipe, null) },
    ) {
        SenderRowContent(group, sims, rules, selected, selecting, actions, background)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SenderRowContent(
    group: SenderGroup,
    sims: List<SimInfo>,
    rules: Map<Long, SpamRule>,
    selected: Boolean,
    selecting: Boolean,
    actions: BlockedActions,
    background: Color,
) {
    val colors = MaterialTheme.colorScheme
    val latest = group.latest
    val time = rememberTimeLabel(latest.receivedAt)
    val count = group.messages.size
    val countText = pluralStringResource(R.plurals.spam_message_count, count, count)
    val unread = group.unread
    val unreadText = if (unread) stringResource(R.string.convlist_unread_count, group.unreadCount) else null
    val badge = unreadBadgeText(group.unreadCount)
    val shown = isolateNumbers(displayNumber(group.address))
    val body = blockedBodyText(LocalResources.current, latest)
    val reason = reasonText(latest, rules)
    val important = latest.reason == DecisionReason.UNKNOWN_SENDER && BlockedHints.looksImportant(latest.body)
    val importantText = stringResource(R.string.blocked_may_be_important)
    val mmsText = mmsMediaLabel(latest)?.takeIf { latest.hasText }?.let { stringResource(it) }
    // One SIM chip when all the sender's messages came on one SIM, as a conversation row shows it.
    val sim = group.messages.map { it.subscriptionId }.distinct().singleOrNull()?.let { simLabel(sims, it) }
    val selectedText = stringResource(if (selected) R.string.spam_state_selected else R.string.spam_state_not_selected)
    val separator = stringResource(R.string.ui_separator)
    // The row no longer shows the reason or the picture state, but TalkBack still reads them.
    val description = listOfNotNull(
        shown, unreadText, countText, body, mmsText, reason, importantText.takeIf { important }, time, sim,
    ).joinToString(separator)
    val openLabel = stringResource(R.string.r3b_open_sender)
    val selectLabel = stringResource(R.string.spam_select)
    val rowActions = listOf(
        stringResource(R.string.r3b_move_inbox_for, shown) to { actions.onNotSpam(setOf(group.address)) },
        stringResource(R.string.r3b_delete_for, shown) to { actions.onDelete(setOf(group.address)) },
    )
    // A selected row sits on primaryContainer, so every text takes its on-color.
    val strong = if (selected) colors.onPrimaryContainer else colors.onSurface
    // As a Messages row: an unread time takes the accent colour (unless the row is selected).
    val timeColor = senderTimeColor(selected, unread)
    val quiet = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant
    val previewColor = if (unread && !selected) colors.onSurface else quiet
    val align = startAlign()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .heightIn(min = RowMinHeight)
            .combinedClickable(
                onClickLabel = if (selecting) selectLabel else openLabel,
                onLongClickLabel = selectLabel,
                onClick = {
                    if (selecting) actions.onToggleSelected(group.address) else actions.onOpenSender(group.address)
                },
                onLongClick = { actions.onToggleSelected(group.address) },
            )
            .semantics(mergeDescendants = true) {
                contentDescription = description
                stateDescription = selectedText
                this.selected = selected
                // Swipe and the thread's buttons are not one gesture away for TalkBack, so mirror them here.
                if (!selecting) {
                    customActions = rowActions.map { (label, run) -> CustomAccessibilityAction(label) { run(); true } }
                }
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SenderAvatar(label = group.address, selected = selected)
        // The two lines of a Messages tab row (ConversationItem), with the same styles and sizes.
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SenderTitleLine(shown, time, important, importantText, selected, unread, align, strong, timeColor)
            SenderPreviewLine(isolateNumbers(body), sim, selected, unread, align, previewColor, badge)
        }
    }
}

@Composable
private fun unreadBadgeText(unreadCount: Int): String =
    if (unreadCount > MAX_UNREAD_BADGE) {
        localizeDigits("$MAX_UNREAD_BADGE+", uiLocale())
    } else {
        localizedNumber(unreadCount, uiLocale())
    }

@Composable
private fun senderTimeColor(selected: Boolean, unread: Boolean): Color {
    val colors = MaterialTheme.colorScheme
    return if (selected) colors.onPrimaryContainer else if (unread) colors.primary else colors.onSurfaceVariant
}

@Composable
private fun SenderTitleLine(
    shown: String,
    time: String,
    important: Boolean,
    importantText: String,
    selected: Boolean,
    unread: Boolean,
    align: TextAlign,
    strong: Color,
    timeColor: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            shown,
            style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Content, textAlign = align),
            color = strong,
            fontWeight = unreadTitleWeight(unread),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (important) ImportantChip(importantText, selected)
        Text(
            time,
            style = MaterialTheme.typography.labelMedium,
            color = timeColor,
            fontWeight = unreadTitleWeight(unread),
            maxLines = 1,
        )
    }
}

@Composable
private fun SenderPreviewLine(
    body: String,
    sim: String?,
    selected: Boolean,
    unread: Boolean,
    align: TextAlign,
    previewColor: Color,
    badge: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content, textAlign = align),
            color = previewColor,
            fontWeight = unreadPreviewWeight(unread),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        sim?.let { RowChip(it, selected) }
        // The Messages row's own badge, counting the unread messages of the sender.
        if (unread) UnreadBadge(badge)
    }
}

/**
 * "May be important", the one held-why signal a row still shows (round 4): a small chip beside the time, where it
 * does not cost the preview any width. The plain reason ("Unknown sender", a rule's name) is left to the filter chips
 * and the thread's "Why blocked", since nearly every row would repeat the same words.
 */
@Composable
private fun ImportantChip(text: String, selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (selected) colors.onPrimaryContainer else colors.onTertiaryContainer,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) colors.surfaceContainerHigh else colors.tertiaryContainer)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** "SIM 2", the same quiet chip the Messages tab rows show. */
@Composable
private fun RowChip(text: String, selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colors.surfaceContainerHigh)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}
