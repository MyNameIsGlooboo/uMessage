package com.glooboo.umessage.ui.details

import com.glooboo.umessage.ui.components.displayNumber
import com.glooboo.umessage.ui.components.isolateNumbers
import com.glooboo.umessage.ui.components.asciiDigits
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.components.ContactRow
import com.glooboo.umessage.ui.components.LinkRow
import com.glooboo.umessage.ui.components.LoadingState
import com.glooboo.umessage.ui.components.SectionHeader
import com.glooboo.umessage.ui.components.UMessageScaffold
import com.glooboo.umessage.ui.components.UMessageTopBar
import com.glooboo.umessage.ui.components.SwitchRow
import com.glooboo.umessage.ui.conversations.ContactAvatar
import com.glooboo.umessage.ui.icons.Symbols
import org.fossify.commons.models.SimpleContact

/**
 * Header card, name, per-conversation notifications, sender blocking and members of one conversation.
 * [phoneNumber] empty hides the contact actions; [blocked] null hides the block row (groups, unknown state).
 * [savedContact] (B6, B14): the sender is in the address book, so the header offers "View contact" instead of "Add
 * contact" and "Always allow" is not offered (a contact's texts reach the inbox anyway) unless a rule is already on.
 */
@Composable
fun ConversationDetailsScreen(
    loaded: Boolean,
    name: String,
    participants: List<SimpleContact>,
    customNotifications: Boolean,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onCustomNotificationsChange: (Boolean) -> Unit,
    onOpenChannelSettings: () -> Unit,
    onOpenParticipant: (SimpleContact) -> Unit,
    modifier: Modifier = Modifier,
    phoneNumber: String = "",
    photoUri: String = "",
    isGroup: Boolean = false,
    canCall: Boolean = false,
    blocked: Boolean? = null,
    onCall: () -> Unit = {},
    onCopyNumber: () -> Unit = {},
    onAddContact: () -> Unit = {},
    onBlockedChange: (Boolean) -> Unit = {},
    /** ID-50: whether an allow rule covers this sender; null hides the row (with [blocked]). */
    allowed: Boolean? = null,
    allowSpoofable: Boolean = false,
    onAllowedChange: (Boolean) -> Unit = {},
    savedContact: Boolean = false,
    onViewContact: () -> Unit = {},
    /** CL-23: opens "Search in conversation"; null hides the row. */
    onSearch: (() -> Unit)? = null,
    /** ID-50: opens the forward rules for this sender; null hides the row (groups). */
    onForwardRule: (() -> Unit)? = null,
    /** I56: the pictures and links of this conversation; null while they load, and nothing shows without any. */
    media: ConversationMedia? = null,
    mediaActions: MediaActions = MediaActions(),
    /** N05: what the Mute row says ("Off", "Always", "Until ..."); null hides the row. */
    muteSummary: String? = null,
    onMute: () -> Unit = {},
) {
    UMessageScaffold(
        modifier = modifier,
        topBar = { UMessageTopBar(title = stringResource(R.string.conversation_details), onBack = onBack) },
    ) { padding ->
        if (!loaded) {
            LoadingState(Modifier.padding(top = padding.calculateTopPadding()))
            return@UMessageScaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 8.dp,
            ),
        ) {
            item(key = "header") {
                DetailsHeader(
                    name = name,
                    phoneNumber = phoneNumber,
                    photoUri = photoUri,
                    isGroup = isGroup,
                    canCall = canCall,
                    onCall = onCall,
                    onCopyNumber = onCopyNumber,
                    onRename = onRename,
                    contactAction = if (savedContact) {
                        HeaderActionSpec(Symbols.Contacts, R.string.inbox_details_view_contact, onViewContact)
                    } else {
                        HeaderActionSpec(Symbols.PersonAdd, R.string.convx_details_add_contact, onAddContact)
                    },
                )
            }

            item(key = "notifications-header") {
                SectionHeader(stringResource(org.fossify.commons.R.string.notifications))
            }
            item(key = "notifications-switch") {
                SwitchRow(
                    title = stringResource(R.string.enable_custom_notifications),
                    body = "",
                    checked = customNotifications,
                    onCheckedChange = onCustomNotificationsChange,
                )
            }
            if (muteSummary != null) {
                item(key = "notifications-mute") {
                    LinkRow(
                        title = stringResource(R.string.mute_title),
                        body = muteSummary,
                        onClick = onMute,
                        trailing = {
                            Icon(
                                Symbols.NotificationsOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }
            if (customNotifications) {
                item(key = "notifications-channel") {
                    LinkRow(
                        title = stringResource(org.fossify.commons.R.string.customize_notifications),
                        body = "",
                        onClick = onOpenChannelSettings,
                        trailing = {
                            Icon(
                                Symbols.NotificationsActive,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                }
            }

            if (blocked != null) {
                statusItems(
                    blocked = blocked,
                    onBlockedChange = onBlockedChange,
                    // B14: a saved contact's texts reach the inbox anyway; an existing allow rule can still be removed.
                    allowed = allowed?.takeIf { !savedContact || it },
                    allowSpoofable = allowSpoofable,
                    onAllowedChange = onAllowedChange,
                )
            }
            moreItems(onSearch, onForwardRule, withHeader = blocked == null)
            mediaItems(media, mediaActions)

            // CL-25: "Members" is a group feature; a one-to-one chat is described by the header above.
            if (isGroup || participants.size > 1) {
                item(key = "members-header") { SectionHeader(stringResource(R.string.members)) }
                items(participants) { contact ->
                    ContactRow(
                        name = isolateNumbers(displayNumber(contact.name)),
                        supporting = contact.phoneNumbers
                            .map { isolateNumbers(displayNumber(it.normalizedNumber)) }
                            .joinToString(", "),
                        photoUri = contact.photoUri,
                        onClick = { onOpenParticipant(contact) },
                    )
                }
            }
        }
    }
}

/**
 * U50: the sender's one choice (U15), under the one "More" header that Search follows. Always allow is offered when
 * [allowed] is not null.
 */
private fun LazyListScope.statusItems(
    blocked: Boolean,
    onBlockedChange: (Boolean) -> Unit,
    allowed: Boolean?,
    allowSpoofable: Boolean,
    onAllowedChange: (Boolean) -> Unit,
) {
    item(key = "block-header") { SectionHeader(stringResource(R.string.inbox_details_more)) }
    item(key = "sender-choice") {
        val current = senderChoice(blocked, allowed)
        val choices = if (allowed == null) {
            listOf(SenderChoice.NORMAL, SenderChoice.BLOCK)
        } else {
            SenderChoice.entries
        }
        SenderChoiceRow(
            current = current,
            choices = choices,
            // The warning about a sender name anyone can fake stays; the plain hints go.
            warning = allowSpoofable && current == SenderChoice.ALLOW,
            onChoose = { applySenderChoice(it, current, onBlockedChange, onAllowedChange) },
        )
    }
}

/** A segmented row of the sender's choices: only one can be on, as the control shows. */
@Composable
private fun SenderChoiceRow(
    current: SenderChoice,
    choices: List<SenderChoice>,
    warning: Boolean,
    onChoose: (SenderChoice) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.ux8c_sender_title), style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            choices.forEachIndexed { index, choice ->
                SegmentedButton(
                    selected = choice == current,
                    onClick = { onChoose(choice) },
                    shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = colors.secondaryContainer,
                        activeContentColor = colors.onSecondaryContainer,
                        activeBorderColor = colors.outline,
                        inactiveContainerColor = Color.Transparent,
                        inactiveContentColor = colors.onSurface,
                        inactiveBorderColor = colors.outline,
                    ),
                ) {
                    Text(stringResource(choiceLabel(choice)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (warning) {
            Text(
                stringResource(R.string.w3c_details_allow_spoof_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

private fun choiceLabel(choice: SenderChoice): Int = when (choice) {
    SenderChoice.NORMAL -> R.string.ux8c_sender_normal
    SenderChoice.ALLOW -> R.string.ux8c_sender_allow
    SenderChoice.BLOCK -> R.string.ux8c_sender_block
}

/** CL-23 / ID-50: "More": Search in conversation and the forward rule for this sender, each when given. */
private fun LazyListScope.moreItems(
    onSearch: (() -> Unit)?,
    onForwardRule: (() -> Unit)?,
    withHeader: Boolean,
) {
    if (onSearch == null && onForwardRule == null) return
    if (withHeader) item(key = "more-header") { SectionHeader(stringResource(R.string.inbox_details_more)) }
    onSearch?.let { search ->
        item(key = "search") {
            LinkRow(
                title = stringResource(R.string.inbox_details_search),
                body = stringResource(R.string.inbox_details_search_body),
                onClick = search,
                trailing = { RowIcon(Symbols.Search) },
            )
        }
    }
    onForwardRule?.let { forward ->
        item(key = "forward") {
            LinkRow(
                title = stringResource(R.string.inbox_details_forward),
                body = stringResource(R.string.inbox_details_forward_body),
                onClick = forward,
                trailing = { RowIcon(Symbols.Forward) },
            )
        }
    }
}

/** Icon, label and action of one header button. */
private class HeaderActionSpec(val icon: ImageVector, val labelRes: Int, val onClick: () -> Unit)

@Composable
private fun RowIcon(icon: ImageVector) {
    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * Large avatar, the name (B6) and the number, and Call / Copy / Add or View contact for a one-to-one conversation
 * (CL-23, CL-25). Name and number use the shared display form (CL-10).
 */
@Composable
private fun DetailsHeader(
    name: String,
    phoneNumber: String,
    photoUri: String,
    isGroup: Boolean,
    canCall: Boolean,
    onCall: () -> Unit,
    onCopyNumber: () -> Unit,
    onRename: () -> Unit,
    contactAction: HeaderActionSpec,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ContactAvatar(title = name, photoUri = photoUri, isGroup = isGroup, size = 72.dp)
        // U38: the name once, with the pencil beside it; a tap on either renames (the note lives in the dialog).
        Row(
            modifier = Modifier.clickable(role = Role.Button, onClick = onRename),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (name.isNotBlank()) {
                Text(
                    text = isolateNumbers(displayNumber(name)),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            Icon(
                Symbols.Edit,
                contentDescription = stringResource(R.string.screens_details_edit_name),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp).size(20.dp),
            )
        }
        val showNumber = !isGroup && phoneNumber.isNotBlank() && asciiDigits(phoneNumber) != asciiDigits(name)
        if (showNumber) {
            Text(
                text = isolateNumbers(displayNumber(phoneNumber)),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (!isGroup && phoneNumber.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                if (canCall) HeaderAction(Symbols.Call, stringResource(R.string.convx_details_call), onCall)
                HeaderAction(Symbols.ContentCopy, stringResource(R.string.convx_details_copy), onCopyNumber)
                HeaderAction(contactAction.icon, stringResource(contactAction.labelRes), contactAction.onClick)
            }
        }
    }
}

@Composable
private fun HeaderAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}
