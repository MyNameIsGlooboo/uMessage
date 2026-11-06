package com.glooboo.umessage.ui.conversations

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.glooboo.umessage.R
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.ui.components.ConfirmDialog
import com.glooboo.umessage.ui.components.EmptyState
import com.glooboo.umessage.ui.components.LoadingState
import com.glooboo.umessage.ui.components.LongSetStateSaver
import com.glooboo.umessage.ui.components.SelectionTopBar
import com.glooboo.umessage.ui.components.UMessageScaffold
import com.glooboo.umessage.ui.components.UMessageTopBar
import com.glooboo.umessage.ui.components.displayTitle
import com.glooboo.umessage.ui.components.formatListTimeStyled
import com.glooboo.umessage.ui.components.localizeDigits
import com.glooboo.umessage.ui.components.localizedNumber
import com.glooboo.umessage.ui.components.uiLocale
import com.glooboo.umessage.ui.icons.Symbols

/** Bringing chats back to Messages: icon, action label, and the confirmation's title and message ("N chats"). */
class FolderRestore(val icon: ImageVector, val labelRes: Int, val titleRes: Int, val messageRes: Int)

/**
 * Emptying a folder for good: the overflow item, an action (CL-31), and the dialog that asks the question
 * ([titleRes]) with its warning ([messageRes]).
 */
class FolderEmptying(val menuRes: Int, val titleRes: Int, val messageRes: Int)

/**
 * The two folders that hold conversations out of the main list; they differ only in labels, icon and the "bring back"
 * action.
 */
enum class ConversationFolder(
    val titleRes: Int,
    val emptyTitleRes: Int,
    val emptyBodyRes: Int,
    val restore: FolderRestore,
    val emptying: FolderEmptying,
) {
    Archive(
        titleRes = R.string.nav_archived_title,
        emptyTitleRes = R.string.screens_archive_empty_title,
        // B13: one sentence that adds to the title instead of repeating it.
        emptyBodyRes = R.string.inbox_archive_empty_body,
        restore = FolderRestore(
            Symbols.Unarchive, R.string.unarchive, R.string.fixes_unarchive_title, R.string.fixes_unarchive_message,
        ),
        emptying = FolderEmptying(
            R.string.inbox_empty_archive_menu,
            R.string.screens_empty_archive_title,
            R.string.empty_archive_confirmation,
        ),
    ),
    RecycleBin(
        titleRes = org.fossify.commons.R.string.recycle_bin,
        emptyTitleRes = R.string.screens_bin_empty_title,
        emptyBodyRes = R.string.screens_bin_empty_body,
        restore = FolderRestore(
            Symbols.RestoreFromTrash, R.string.restore, R.string.screens_restore_title, R.string.restore_confirmation,
        ),
        emptying = FolderEmptying(
            R.string.inbox_empty_bin_menu,
            R.string.screens_empty_bin_title,
            R.string.empty_recycle_bin_messages_confirmation,
        ),
    ),
}

/**
 * List row for a stored conversation. Archive and bin show no SIM chip, mute or pin state, only what identifies the
 * thread.
 */
fun Conversation.toFolderRow(context: Context, drafts: Map<Long, String>, pinned: Set<String>): ConversationRow {
    val draft = drafts[threadId]
    val hasDraft = !draft.isNullOrEmpty()
    val unread = !read
    return ConversationRow(
        threadId = threadId,
        // CL-33: the same display form as the Messages list ("985-0003", Persian digits in the Persian UI).
        title = displayTitle(context, title),
        snippet = (if (hasDraft) draft else snippet).orEmpty().replace('\n', ' '),
        dateLabel = formatListTimeStyled(context, date * 1000L),
        unread = unread,
        unreadBadge = when {
            !unread || unreadCount <= 0 -> ""
            unreadCount > MAX_FOLDER_BADGE -> localizeDigits("$MAX_FOLDER_BADGE+", context.uiLocale)
            else -> localizedNumber(unreadCount, context.uiLocale)
        },
        unreadCount = unreadCount,
        pinned = pinned.contains(threadId.toString()),
        muted = false,
        hasDraft = hasDraft,
        scheduled = isScheduled,
        simSlot = null,
        photoUri = photoUri,
        isGroup = isGroupConversation,
        phoneNumber = phoneNumber,
    )
}

private const val MAX_FOLDER_BADGE = 99

/**
 * Archive / Recycle Bin screen: the shared conversation row, long-press multi-select with a contextual bar,
 * confirmation before anything is removed for good.
 */
@Composable
fun ConversationFolderScreen(
    folder: ConversationFolder,
    rows: List<ConversationRow>,
    loading: Boolean,
    /** Null on a tab: no back arrow. */
    onBack: (() -> Unit)?,
    onOpen: (ConversationRow) -> Unit,
    onRestore: (Set<Long>) -> Unit,
    onDelete: (Set<Long>) -> Unit,
    onEmpty: () -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
    /** On the Archived tab: leaves the empty state for Messages. */
    onBackToMessages: (() -> Unit)? = null,
    /** Adds "Mark all read" to the overflow menu when given. */
    onMarkAllRead: (() -> Unit)? = null,
    /** A short explanation above the list. */
    intro: (@Composable () -> Unit)? = null,
) {
    var selected by rememberSaveable(saver = LongSetStateSaver) { mutableStateOf(emptySet()) }
    var confirmDelete by remember { mutableStateOf<Set<Long>?>(null) }
    var confirmRestore by remember { mutableStateOf<Set<Long>?>(null) }
    var confirmEmpty by rememberSaveable { mutableStateOf(false) }
    // Rows that vanished (restored, deleted elsewhere) cannot stay selected.
    val visibleSelection = remember(selected, rows) {
        selected.filterTo(HashSet()) { id -> rows.any { it.threadId == id } }
    }
    val selecting = visibleSelection.isNotEmpty()
    BackHandler(enabled = selecting) { selected = emptySet() }

    val callbacks = remember(rows, visibleSelection) {
        ConversationsCallbacks(
            onOpen = onOpen,
            onToggleSelect = { id ->
                selected = if (id in visibleSelection) visibleSelection - id else visibleSelection + id
            },
            onStartSelect = { id -> selected = visibleSelection + id },
            onDeleteOne = { id -> confirmDelete = setOf(id) },
        )
    }

    UMessageScaffold(
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        topBar = {
            if (selecting) {
                SelectionTopBar(count = visibleSelection.size, onClose = { selected = emptySet() }) {
                    IconButton(onClick = { confirmRestore = visibleSelection }) {
                        Icon(folder.restore.icon, contentDescription = stringResource(folder.restore.labelRes))
                    }
                    IconButton(onClick = { confirmDelete = visibleSelection }) {
                        Icon(Symbols.Delete, contentDescription = stringResource(R.string.screens_delete))
                    }
                    IconButton(onClick = { selected = rows.mapTo(HashSet()) { it.threadId } }) {
                        Icon(Symbols.SelectAll, contentDescription = stringResource(R.string.screens_select_all))
                    }
                }
            } else {
                FolderTopBar(
                    folder = folder,
                    onBack = onBack,
                    hasRows = rows.isNotEmpty(),
                    onMarkAllRead = onMarkAllRead?.takeIf { rows.any { it.unread } },
                    onEmpty = { confirmEmpty = true },
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            when {
                loading && rows.isEmpty() -> LoadingState()
                rows.isEmpty() -> FolderEmptyState(folder, onBackToMessages, Modifier.align(Alignment.Center))
                else -> FolderList(
                    folder = folder,
                    rows = rows,
                    selection = visibleSelection,
                    callbacks = callbacks,
                    onRestore = onRestore,
                    bottomPadding = padding.calculateBottomPadding(),
                    intro = intro,
                )
            }
        }
    }

    FolderDialogs(
        folder = folder,
        rowCount = rows.size,
        confirmDelete = confirmDelete,
        confirmRestore = confirmRestore,
        confirmEmpty = confirmEmpty,
        onDismiss = {
            confirmDelete = null
            confirmRestore = null
            confirmEmpty = false
        },
        onClearSelection = { selected = emptySet() },
        onDelete = onDelete,
        onRestore = onRestore,
        onEmpty = onEmpty,
    )
}

/** Title, and an overflow menu with "Mark all read" (when given) and "Empty …" while the folder holds chats. */
@Composable
private fun FolderTopBar(
    folder: ConversationFolder,
    onBack: (() -> Unit)?,
    hasRows: Boolean,
    onMarkAllRead: (() -> Unit)?,
    onEmpty: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    UMessageTopBar(title = stringResource(folder.titleRes), onBack = onBack) {
        // A labelled menu instead of a lone trash icon that looked like "clear all" (CL-31).
        if (hasRows) {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Symbols.MoreVert, contentDescription = stringResource(R.string.nav_folder_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    onMarkAllRead?.let { markAllRead ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.nav_folder_mark_all_read)) },
                            leadingIcon = { Icon(Symbols.DoneAll, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                markAllRead()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(folder.emptying.menuRes)) },
                        leadingIcon = { Icon(Symbols.DeleteSweep, contentDescription = null) },
                        onClick = {
                            menuOpen = false
                            onEmpty()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FolderEmptyState(
    folder: ConversationFolder,
    onBackToMessages: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    EmptyState(
        icon = if (folder == ConversationFolder.Archive) Symbols.Archive else Symbols.Delete,
        title = stringResource(folder.emptyTitleRes),
        body = stringResource(folder.emptyBodyRes),
        modifier = modifier,
        // P3-03: the Archived tab stays while it is open; leaving is the user's choice.
        action = onBackToMessages?.let { back ->
            { Button(onClick = back) { Text(stringResource(R.string.nav_archived_back)) } }
        },
    )
}

@Composable
private fun FolderList(
    folder: ConversationFolder,
    rows: List<ConversationRow>,
    selection: Set<Long>,
    callbacks: ConversationsCallbacks,
    onRestore: (Set<Long>) -> Unit,
    bottomPadding: Dp,
    intro: (@Composable () -> Unit)?,
) {
    // Archive only: a swipe either way brings the chat back to Messages. The tab's Undo snackbar follows onRestore.
    val unarchiveLabel = stringResource(R.string.unarchive)
    val unarchiveAction = remember(unarchiveLabel, onRestore) {
        SwipeAction(Symbols.Unarchive, unarchiveLabel) { id -> onRestore(setOf(id)) }
    }
    val selecting = selection.isNotEmpty()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = bottomPadding + 8.dp),
    ) {
        intro?.let { item(key = "intro") { it() } }
        items(items = rows, key = { it.threadId }, contentType = { "conversation" }) { row ->
            if (folder == ConversationFolder.Archive) {
                SwipeableConversation(
                    row = row,
                    selected = row.threadId in selection,
                    selecting = selecting,
                    canArchive = false,
                    callbacks = callbacks,
                    startToEnd = unarchiveAction,
                    endToStart = unarchiveAction,
                    modifier = Modifier.animateItem(),
                )
            } else {
                ConversationItem(
                    row = row,
                    selected = row.threadId in selection,
                    selecting = selecting,
                    callbacks = callbacks,
                    canArchive = false,
                )
            }
        }
    }
}

/** The three confirmations: delete for good, bring back, and empty the whole folder. */
@Composable
private fun FolderDialogs(
    folder: ConversationFolder,
    rowCount: Int,
    confirmDelete: Set<Long>?,
    confirmRestore: Set<Long>?,
    confirmEmpty: Boolean,
    onDismiss: () -> Unit,
    onClearSelection: () -> Unit,
    onDelete: (Set<Long>) -> Unit,
    onRestore: (Set<Long>) -> Unit,
    onEmpty: () -> Unit,
) {
    confirmDelete?.let { ids ->
        val items = pluralStringResource(R.plurals.delete_conversations, ids.size, ids.size)
        ConfirmDialog(
            title = stringResource(R.string.screens_delete_forever_title),
            message = stringResource(R.string.screens_delete_conversations_message, items),
            confirmLabel = stringResource(R.string.screens_delete),
            destructive = true,
            onConfirm = {
                onDismiss()
                onClearSelection()
                onDelete(ids)
            },
            onDismiss = onDismiss,
        )
    }
    confirmRestore?.let { ids ->
        val items = pluralStringResource(R.plurals.delete_conversations, ids.size, ids.size)
        ConfirmDialog(
            title = stringResource(folder.restore.titleRes),
            message = stringResource(folder.restore.messageRes, items),
            confirmLabel = stringResource(folder.restore.labelRes),
            onConfirm = {
                onDismiss()
                onClearSelection()
                onRestore(ids)
            },
            onDismiss = onDismiss,
        )
    }
    if (confirmEmpty) {
        val count = pluralStringResource(R.plurals.delete_conversations, rowCount, rowCount)
        ConfirmDialog(
            title = stringResource(folder.emptying.titleRes),
            message = stringResource(folder.emptying.messageRes) + " " +
                stringResource(R.string.nav_folder_delete_all_count, count),
            confirmLabel = stringResource(R.string.screens_empty_action),
            destructive = true,
            onConfirm = {
                onDismiss()
                onClearSelection()
                onEmpty()
            },
            onDismiss = onDismiss,
        )
    }
}
