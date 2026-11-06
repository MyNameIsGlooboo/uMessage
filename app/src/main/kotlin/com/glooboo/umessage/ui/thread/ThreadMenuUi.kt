package com.glooboo.umessage.ui.thread

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.icons.Symbols

/**
 * The thread's overflow menu: the short list of [layoutThreadMenu] and, behind "More...", a sheet with the other
 * entries in groups. Every pick goes to [onAction] once the menu or sheet is closed.
 */
@Composable
internal fun ThreadOverflowMenu(menu: ThreadMenuState, onAction: (ThreadMenuAction) -> Unit) {
    val layout = remember(menu) { layoutThreadMenu(menu) }
    val errorColor = MaterialTheme.colorScheme.error
    var open by remember { mutableStateOf(false) }
    var sheetOpen by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Symbols.MoreVert, contentDescription = stringResource(R.string.thread_more_options))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            layout.top.forEach { action ->
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(menuLabel(action)),
                            color = if (action == ThreadMenuAction.Delete) errorColor else Color.Unspecified,
                        )
                    },
                    leadingIcon = {
                        Icon(
                            menuIcon(action),
                            contentDescription = null,
                            tint = if (action == ThreadMenuAction.Delete) {
                                errorColor
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    onClick = {
                        open = false
                        onAction(action)
                    },
                )
            }
            if (layout.hasMore) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.thread_menu_more)) },
                    leadingIcon = { Icon(Symbols.MoreVert, contentDescription = null) },
                    onClick = {
                        open = false
                        sheetOpen = true
                    },
                )
            }
        }
    }
    if (sheetOpen) {
        MoreSheet(
            sections = layout.more,
            onDismiss = { sheetOpen = false },
            onPick = {
                sheetOpen = false
                onAction(it)
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreSheet(sections: List<ThreadMoreSection>, onDismiss: () -> Unit, onPick: (ThreadMenuAction) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            sections.forEachIndexed { index, section ->
                val title = sectionTitle(section.group)
                if (title == null) {
                    if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                } else {
                    Text(
                        stringResource(title),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = if (index > 0) 12.dp else 0.dp),
                    )
                }
                section.actions.forEach { action -> MoreRow(action) { onPick(action) } }
            }
        }
    }
}

@Composable
private fun MoreRow(action: ThreadMenuAction, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val color = if (action == ThreadMenuAction.Delete) colors.error else colors.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Icon(menuIcon(action), contentDescription = null, tint = color)
        Text(stringResource(menuLabel(action)), style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

private fun sectionTitle(group: ThreadMoreGroup): Int? = when (group) {
    ThreadMoreGroup.ShareExport -> R.string.thread_menu_group_share
    ThreadMoreGroup.Organize -> R.string.thread_menu_group_organize
    ThreadMoreGroup.Sender -> R.string.thread_menu_group_sender
    ThreadMoreGroup.Danger -> null
}

@Suppress("CyclomaticComplexMethod")
private fun menuLabel(action: ThreadMenuAction): Int = when (action) {
    ThreadMenuAction.Details -> R.string.thread_menu_details
    ThreadMenuAction.Search -> R.string.thread_r2_menu_search
    ThreadMenuAction.Rename -> R.string.thread_menu_rename
    ThreadMenuAction.ShareAsText -> R.string.share_text_menu
    ThreadMenuAction.ExportConversation -> R.string.export_conv_menu
    ThreadMenuAction.AddToHomeScreen -> R.string.home_shortcut_menu
    ThreadMenuAction.MarkUnread -> R.string.thread_menu_mark_unread
    ThreadMenuAction.Labels -> R.string.i29_menu_labels
    ThreadMenuAction.Mute -> R.string.mute_menu
    ThreadMenuAction.Unmute -> R.string.unmute_menu
    ThreadMenuAction.Hide -> R.string.hidden_chats_menu_hide
    ThreadMenuAction.Unhide -> R.string.hidden_chats_menu_unhide
    ThreadMenuAction.Archive -> R.string.thread_menu_archive
    ThreadMenuAction.Unarchive -> R.string.thread_menu_unarchive
    ThreadMenuAction.CopyNumber -> R.string.thread_menu_copy_number
    ThreadMenuAction.AddToContact -> R.string.thread_menu_add_contact
    ThreadMenuAction.AllowSender -> R.string.w2b2_thread_menu_allow
    ThreadMenuAction.WhyDecided -> R.string.w2b2_thread_menu_why
    ThreadMenuAction.ReportSpam -> R.string.thread_menu_report_spam
    ThreadMenuAction.Block -> R.string.thread_menu_block
    ThreadMenuAction.Unblock -> R.string.thread_menu_unblock
    ThreadMenuAction.AlwaysForward -> R.string.thread_r2_menu_always_forward
    ThreadMenuAction.ManagePeople -> R.string.thread_menu_add_people
    ThreadMenuAction.Restore -> R.string.thread_menu_restore_all
    ThreadMenuAction.Delete -> R.string.thread_menu_delete
    ThreadMenuAction.Dial -> R.string.thread_call
}

@Suppress("CyclomaticComplexMethod")
private fun menuIcon(action: ThreadMenuAction): ImageVector = when (action) {
    ThreadMenuAction.Details -> Symbols.Info
    ThreadMenuAction.Search -> Symbols.Search
    ThreadMenuAction.Rename -> Symbols.Edit
    ThreadMenuAction.ShareAsText -> Symbols.Forward
    ThreadMenuAction.ExportConversation -> Symbols.Upload
    ThreadMenuAction.AddToHomeScreen -> Symbols.Smartphone
    ThreadMenuAction.MarkUnread -> Symbols.ChatBubble
    ThreadMenuAction.Labels -> Symbols.Tag
    ThreadMenuAction.Mute -> Symbols.NotificationsOff
    ThreadMenuAction.Unmute -> Symbols.Notifications
    ThreadMenuAction.Hide -> Symbols.Lock
    ThreadMenuAction.Unhide -> Symbols.Visibility
    ThreadMenuAction.Archive -> Symbols.Archive
    ThreadMenuAction.Unarchive -> Symbols.Unarchive
    ThreadMenuAction.CopyNumber -> Symbols.ContentCopy
    ThreadMenuAction.AddToContact -> Symbols.PersonAdd
    ThreadMenuAction.AllowSender -> Symbols.Shield
    ThreadMenuAction.WhyDecided -> Symbols.Rule
    ThreadMenuAction.ReportSpam -> Symbols.Warning
    ThreadMenuAction.Block, ThreadMenuAction.Unblock -> Symbols.Block
    ThreadMenuAction.AlwaysForward -> Symbols.ArrowForward
    ThreadMenuAction.ManagePeople -> Symbols.Group
    ThreadMenuAction.Restore -> Symbols.RestoreFromTrash
    ThreadMenuAction.Delete -> Symbols.Delete
    ThreadMenuAction.Dial -> Symbols.Call
}
