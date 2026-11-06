package com.glooboo.umessage.ui.thread

import com.glooboo.umessage.ui.components.uiLocale
import com.glooboo.umessage.ui.icons.Symbols
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.glooboo.umessage.helpers.DateStyle
import com.glooboo.umessage.helpers.w3cPrefs
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.models.Message
import java.time.ZoneId
import android.text.format.DateFormat
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.glooboo.umessage.ui.components.formatClockTime

private val AvatarSize = 32.dp
private val SelectionMarkSize = 22.dp
private const val BUBBLE_WIDTH_FRACTION = 0.8f

@Composable
fun LetterAvatar(label: String, size: Dp, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(colors.primaryContainer)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        val initial = label.firstOrNull { it.isLetter() }?.uppercaseChar()?.toString()
        if (initial == null) {
            Icon(Symbols.PersonFilled, contentDescription = null, tint = colors.onPrimaryContainer, modifier = Modifier.size(size * 0.6f))
        } else {
            Text(initial, style = MaterialTheme.typography.labelLarge, color = colors.onPrimaryContainer)
        }
    }
}

/** Contact photo when there is one, otherwise the letter avatar. */
@Composable
private fun ChatAvatar(name: String, photoUri: String, onClick: () -> Unit) {
    val density = LocalContext.current.resources.displayMetrics.density
    val px = (AvatarSize.value * density).toInt()
    val load = rememberGlideBitmap(photoUri.ifBlank { null }, px, px).value
    val bitmap = (load as? ImageLoad.Loaded)?.bitmap
    val description = stringResource(R.string.thread_sender_photo, name)
    Box(
        modifier = Modifier
            .size(AvatarSize)
            .clip(CircleShape)
            .clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        LetterAvatar(label = name, size = AvatarSize)
        if (bitmap != null) {
            Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(AvatarSize))
        }
    }
}

@Composable
fun ThreadRowItem(row: ThreadRow, vm: ThreadViewModel, actions: ThreadActions) {
    when (row) {
        is ThreadRow.DateHeader -> DateHeader(row, showSim = vm.sims.size > 1)
        is ThreadRow.Bubble -> MessageBubbleRow(row, vm, actions)
        is ThreadRow.Sending -> StatusLine(text = stringResource(R.string.thread_status_sending))

        is ThreadRow.Sent -> StatusLine(
            text = stringResource(if (row.delivered) R.string.thread_status_delivered else R.string.thread_status_sent),
            icon = if (row.delivered) Symbols.DoneAll else Symbols.Check,
        )

        is ThreadRow.UnreadDivider -> UnreadDivider()

        is ThreadRow.Failed -> StatusLine(
            text = stringResource(R.string.thread_status_failed),
            icon = Symbols.Error,
            color = MaterialTheme.colorScheme.error,
            onClick = { actions.onRetry(row.message, row.text) },
        )
    }
}

@Composable
private fun DateHeader(row: ThreadRow.DateHeader, showSim: Boolean) {
    val context = LocalContext.current
    val words = RelativeDateWords(
        stringResource(R.string.thread_today),
        stringResource(R.string.thread_yesterday),
        stringResource(R.string.ui_separator),
    )
    val use24Hour = DateFormat.is24HourFormat(context)
    val locale = uiLocale()
    val dateStyle = remember { context.w3cPrefs.dateStyle }
    val jalali = dateStyle != DateStyle.GREGORIAN
    val both = dateStyle == DateStyle.BOTH
    val label = remember(row.date, row.showDay, words, use24Hour, locale, dateStyle) {
        val zone = ZoneId.systemDefault()
        val millis = row.date * 1000L
        if (row.showDay) {
            relativeDateLabel(millis, System.currentTimeMillis(), zone, use24Hour, words, locale, jalali, both)
        } else {
            timeOnlyLabel(millis, zone, use24Hour, locale)
        }
    }
    // B16: the separator above a scheduled message says so; a bare time read like a sent time.
    val text = if (row.scheduled) stringResource(R.string.thread_scheduled_for, label) else label
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (row.showDay) 16.dp else 10.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        // Owner notes r2: back to the SIM card icon with the SIM number beside it, as before design review 2;
        // TH-02 still holds: only on dual-SIM phones and only where the SIM changes. TalkBack hears "SIM 2".
        if (showSim && row.showSim) {
            val simDescription = stringResource(R.string.thread_review_sim_label, row.simLabel)
            Row(
                modifier = Modifier.clearAndSetSemantics { contentDescription = simDescription },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Symbols.SimCard,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 8.dp, end = 2.dp)
                        .size(14.dp),
                )
                Text(
                    row.simLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** I26: "Unread messages" between two lines, read by TalkBack as one heading. */
@Composable
private fun UnreadDivider() {
    val color = MaterialTheme.colorScheme.primary
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f), color = color.copy(alpha = 0.5f))
        Text(
            stringResource(R.string.thread_unread_divider),
            style = MaterialTheme.typography.labelMedium,
            color = color,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(modifier = Modifier.weight(1f), color = color.copy(alpha = 0.5f))
    }
}

@Composable
private fun StatusLine(
    text: String,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onClick: (() -> Unit)? = null,
) {
    val retryLabel = stringResource(R.string.ui_retry_send)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    // A real 48dp target with an announced action, not a 2dp-padded line of text.
                    Modifier
                        .heightIn(min = 48.dp)
                        .clickable(onClickLabel = retryLabel, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .padding(end = 12.dp, top = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Spacer(Modifier.size(4.dp))
        }
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubbleRow(row: ThreadRow.Bubble, vm: ThreadViewModel, actions: ThreadActions) {
    val message = row.message
    val sent = !message.isReceivedMessage()
    val selected = message.getStableId() in vm.selectedIds
    val colors = MaterialTheme.colorScheme
    // Received bubbles use the highest surface container so they stay visible on dark backgrounds.
    // TH-31: in a dark theme the sent bubble is a dark accent tone with light text instead of a bright slab.
    val dark = colors.background.luminance() < 0.5f
    // U58: at night the received bubble is one tonal step above the highest surface, so it stands off the page.
    val received = receivedBubbleColor(colors.surfaceContainerHighest, colors.onSurface, dark)
    val (baseContainer, baseContent) = when {
        sent && dark -> darkSentBubbleColors(
            colors.primaryContainer, colors.onPrimaryContainer, colors.primary, colors.background, colors.onSurface,
            received = received,
        )
        sent -> colors.primary to colors.onPrimary
        else -> received to colors.onSurface
    }
    // U49: a selected bubble keeps its own colours; the ring and the check beside it show the selection.
    val containerColor = baseContainer
    val contentColor = baseContent
    val currentMatch = vm.searchOpen && vm.currentSearchMatch == message.key
    val maxBubbleWidth = (LocalConfiguration.current.screenWidthDp * BUBBLE_WIDTH_FRACTION).dp
    val topSpacing = if (row.group == BubbleGroup.First || row.group == BubbleGroup.Single) 6.dp else 2.dp
    val toggle = { vm.toggleSelect(message) }
    val description = bubbleDescription(message, sent)
    val selectLabel = stringResource(R.string.ui_select_message)
    val textSelectable = canSelectTextInPlace(vm.selectedIds, message.getStableId(), message.body)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.primary.copy(alpha = 0.10f) else Color.Transparent)
            // Only clickable while selecting: a disabled clickable still adds a second focusable node for TalkBack.
            .then(
                if (vm.isSelecting) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = toggle,
                    )
                } else {
                    Modifier
                },
            )
            .padding(start = 12.dp, end = 12.dp, top = topSpacing),
        horizontalArrangement = if (sent) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (vm.isSelecting) SelectionMark(selected)
        if (!sent && vm.isGroup) {
            if (row.group == BubbleGroup.Last || row.group == BubbleGroup.Single) {
                ChatAvatar(name = message.senderName, photoUri = message.senderPhotoUri, onClick = { actions.onSenderClick(message) })
            } else {
                Spacer(Modifier.size(AvatarSize))
            }
            Spacer(Modifier.size(8.dp))
        }
        Column(horizontalAlignment = if (sent) Alignment.End else Alignment.Start) {
            if (row.showSender && message.senderName.isNotBlank()) {
                Text(
                    message.senderName,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, bottom = 2.dp),
                )
            }
            Surface(
                shape = bubbleShape(sent, row.group),
                color = containerColor,
                contentColor = contentColor,
                // TH-09: a selected bubble gets a 2dp outline (on a sent bubble in the text colour, which the accent
                // fill would hide). U56: an incoming bubble has no outline. TH-20: the current search match too.
                border = when {
                    selected -> BorderStroke(2.dp, if (sent) colors.onSurface else colors.primary)
                    currentMatch -> BorderStroke(2.dp, colors.tertiary)
                    else -> null
                },
                modifier = Modifier
                    .widthIn(max = maxBubbleWidth)
                    // One node per message: who sent it and when, then the merged message text.
                    .semantics {
                        contentDescription = description
                        this.selected = selected
                    }
                    // The one selected bubble leaves its long press to the text selection (see canSelectTextInPlace);
                    // a tap still reaches the row's clickable above and unselects it, as in Telegram.
                    .then(
                        if (textSelectable) {
                            Modifier
                        } else {
                            Modifier.combinedClickable(
                                onClick = { if (vm.isSelecting) toggle() else actions.onBubbleClick(message) },
                                onLongClick = toggle,
                                onLongClickLabel = selectLabel,
                            )
                        },
                    ),
            ) {
                BubbleContent(
                    message = message,
                    actions = actions,
                    contentColor = contentColor,
                    maxBubbleWidth = maxBubbleWidth,
                    textSelectable = textSelectable,
                    selecting = vm.isSelecting,
                    selected = selected,
                    searchQuery = vm.activeSearchQuery,
                    approximateTime = message.id in vm.approximateScheduledIds,
                    repeatNote = repeatNoteFor(message, vm.scheduledRepeatRules),
                    onToggleSelect = toggle,
                )
            }
            if (vm.stars.isStarred(message)) StarMark()
            vm.reactions[message.getStableId()]?.let {
                ReactionChip(it, Modifier.offset(y = (-8).dp).padding(horizontal = 8.dp))
            }
            if (!sent && !vm.isSelecting && vm.copyChipMessageId == message.getStableId()) {
                CopyChips(message.body)
            }
            if (!sent && !vm.isSelecting && !vm.isGroup) BankAlertCard(message.senderPhoneNumber, message.body)
        }
    }
}

/** I30: the emoji of the iPhone reactions on a message; a tap shows the texts the iPhone really sent. */
@Composable
private fun ReactionChip(reactions: List<TapbackReaction>, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    var showOriginals by remember { mutableStateOf(false) }
    val summary = reactions.joinToString(" ") { if (it.count > 1) "${it.kind.emoji}${it.count}" else it.kind.emoji }
    Surface(
        shape = CircleShape,
        color = colors.surfaceContainerHigh,
        border = BorderStroke(1.dp, colors.outlineVariant),
        modifier = modifier
            .clip(CircleShape)
            .semantics { contentDescription = summary }
            .clickable(onClickLabel = stringResource(R.string.thread_reactions_show_original), role = Role.Button) {
                showOriginals = true
            },
    ) {
        Text(
            summary,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .clearAndSetSemantics { },
        )
    }
    if (showOriginals) {
        AlertDialog(
            onDismissRequest = { showOriginals = false },
            title = { Text(stringResource(R.string.thread_reactions_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    reactions.flatMap { it.originals }.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                }
            },
            confirmButton = {
                TextButton(onClick = { showOriginals = false }) { Text(stringResource(android.R.string.ok)) }
            },
        )
    }
}

/** "You, 6:33 PM" or "Maya, 6:33 PM"; TalkBack reads the merged message text after it. */
@Composable
private fun bubbleDescription(message: Message, sent: Boolean): String {
    val context = LocalContext.current
    val time = remember(message.date) { formatClockTime(context, message.millis()) }
    return when {
        sent -> stringResource(R.string.ui_bubble_sent, time)
        message.senderName.isNotBlank() -> stringResource(R.string.ui_bubble_received, message.senderName, time)
        else -> stringResource(R.string.ui_bubble_received_unknown, time)
    }
}

/** TH-09: while selecting, a check in a filled circle beside each selected bubble. */
@Composable
private fun SelectionMark(selected: Boolean) {
    Box(
        modifier = Modifier
            .padding(end = 8.dp, bottom = 6.dp)
            .size(SelectionMarkSize),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(SelectionMarkSize)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Symbols.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
