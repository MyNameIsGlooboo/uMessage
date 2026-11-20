package com.glooboo.umessage.ui.conversations

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.glooboo.umessage.ui.spam.SpamSelection

/** Bringing chats back to Messages: icon, action label, and the confirmation's title and message ("N chats"). */
class FolderRestore(val icon: ImageVector, val labelRes: Int, val titleRes: Int, val messageRes: Int)

/**
 * Emptying a folder for good: the overflow item, an action (CL-31), and the dialog that asks the question
 * ([titleRes]) with its warning ([messageRes]).
 */
class FolderEmptying(val menuRes: Int, val titleRes: Int, val messageRes: Int)

/**
 * The folders that hold conversations out of the main list; they differ in labels, icon and the "bring back" action.
 * Spam (provider truth, 4.2) has no single "bring back" action and no "Empty": its actions come from
 * [SpamFolderActions].
 */
enum class ConversationFolder(
    val titleRes: Int,
    val emptyTitleRes: Int,
    val emptyBodyRes: Int,
    val restore: FolderRestore?,
    val emptying: FolderEmptying?,
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

    /** Provider truth (4.2, U2): no "Delete all" or "Empty" in this release (answer 17). */
    Spam(
        titleRes = R.string.main_tab_blocked,
        emptyTitleRes = R.string.spam_blocked_empty_title,
        emptyBodyRes = R.string.pt_spam_empty_body,
        restore = null,
        emptying = null,
    ),
}

/**
 * Provider truth (4.2): what the Spam folder offers instead of "bring back". [selection] says which actions the chosen
 * chats allow (a mixed choice only Delete and Mark as read); [swipeFor] is the one swipe direction of a row ("Not spam"
 * or "Unblock" by its cause). [mayDelete] is asked before the delete confirm: false while uMessage is not the default
 * SMS app, and it then says why itself.
 */
class SpamFolderActions(
    val selection: (Set<Long>) -> SpamSelection,
    val onNotSpam: (Set<Long>) -> Unit,
    val onUnblock: (Set<Long>) -> Unit,
    val onBlock: (Set<Long>) -> Unit,
    val onMarkRead: (Set<Long>) -> Unit,
    val mayDelete: () -> Boolean,
    val swipeFor: (ConversationRow) -> SwipeAction?,
)

/** One more entry of a folder's overflow menu (the Spam tab: its settings and rules). */
class FolderMenuEntry(val labelRes: Int, val icon: ImageVector, val onClick: () -> Unit)

/**
 * List row for a stored conversation. Archive and bin show no mute or pin state, only what identifies the thread.
 * [simSlot] and [simName] are the SIM chip of the Messages list (the Archived tab passes them; the bin does not).
 */
fun Conversation.toFolderRow(
    context: Context,
    drafts: Map<Long, String>,
    pinned: Set<String>,
    simSlot: Int? = null,
    simName: String = "",
): ConversationRow {
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
        simSlot = simSlot,
        simName = simName,
        photoUri = photoUri,
        isGroup = isGroupConversation,
        phoneNumber = phoneNumber,
    )
}

private const val MAX_FOLDER_BADGE = 99

/**
 * Messages in the Recycle Bin, so its confirmations say what they delete: per chat, and in all (the whole bin,
 * hidden chats included).
 */
data class BinMessageCounts(val perChat: Map<Long, Int>, val total: Int)

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
    /** On the Recycle Bin screen: how many messages each confirmation deletes. */
    binCounts: BinMessageCounts? = null,
    /** The Spam folder's own actions (provider truth, 4.2). */
    spam: SpamFolderActions? = null,
    /** More entries of the overflow menu, shown also while the folder is empty. */
    menu: List<FolderMenuEntry> = emptyList(),
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
            onDeleteOne = { id -> if (spam?.mayDelete() != false) confirmDelete = setOf(id) },
        )
    }

    UMessageScaffold(
        modifier = modifier,
        snackbarHostState = snackbarHostState,
        topBar = {
            if (selecting) {
                FolderSelectionBar(
                    folder = folder,
                    chosen = visibleSelection,
                    spam = spam,
                    onClose = { selected = emptySet() },
                    onRestore = { confirmRestore = visibleSelection },
                    onDelete = { if (spam?.mayDelete() != false) confirmDelete = visibleSelection },
                    onSelectAll = { selected = rows.mapTo(HashSet()) { it.threadId } },
                )
            } else {
                FolderTopBar(
                    folder = folder,
                    onBack = onBack,
                    hasRows = rows.isNotEmpty(),
                    onMarkAllRead = onMarkAllRead?.takeIf { rows.any { it.unread } },
                    onEmpty = { confirmEmpty = true },
                    more = menu,
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            when {
                loading && rows.isEmpty() -> LoadingState()
                // Spam: the strip ("the filter is off", older spam) stays above an empty list too.
                rows.isEmpty() -> {
                    FolderEmpty(folder, onBackToMessages, intro.takeIf { folder == ConversationFolder.Spam })
                }
                else -> FolderList(
                    folder = folder,
                    rows = rows,
                    selection = visibleSelection,
                    callbacks = callbacks,
                    onRestore = onRestore,
                    bottomPadding = padding.calculateBottomPadding(),
                    intro = intro,
                    spamSwipe = spam?.swipeFor,
                )
            }
        }
    }

    FolderDialogs(
        folder = folder,
        rowCount = rows.size,
        binCounts = binCounts,
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

/** The contextual bar while chats are chosen: bring back (or the Spam actions), Delete and Select all. */
@Composable
private fun FolderSelectionBar(
    folder: ConversationFolder,
    chosen: Set<Long>,
    spam: SpamFolderActions?,
    onClose: () -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    onSelectAll: () -> Unit,
) {
    SelectionTopBar(count = chosen.size, onClose = onClose) {
        folder.restore?.let { restore ->
            IconButton(onClick = onRestore) {
                Icon(restore.icon, contentDescription = stringResource(restore.labelRes))
            }
        }
        spam?.let { SpamSelectionButtons(it, chosen, onClose) }
        IconButton(onClick = onDelete) {
            Icon(Symbols.Delete, contentDescription = stringResource(R.string.screens_delete))
        }
        IconButton(onClick = onSelectAll) {
            Icon(Symbols.SelectAll, contentDescription = stringResource(R.string.screens_select_all))
        }
    }
}

/**
 * The Spam folder's selection actions (4.2): "Not spam" and Block for unknown senders, "Unblock" for blocked ones, and
 * Mark as read; a mixed choice offers only Mark as read here (and Delete beside it).
 */
@Composable
private fun RowScope.SpamSelectionButtons(spam: SpamFolderActions, chosen: Set<Long>, onDone: () -> Unit) {
    val offer = spam.selection(chosen)
    if (offer.canNotSpam) {
        IconButton(onClick = { onDone(); spam.onNotSpam(chosen) }) {
            Icon(Symbols.Inbox, contentDescription = stringResource(R.string.ux8c_not_spam))
        }
    }
    if (offer.canUnblock) {
        IconButton(onClick = { onDone(); spam.onUnblock(chosen) }) {
            Icon(Symbols.Shield, contentDescription = stringResource(R.string.blocknum_unblock))
        }
    }
    if (offer.canBlock) {
        IconButton(onClick = { onDone(); spam.onBlock(chosen) }) {
            Icon(Symbols.Block, contentDescription = stringResource(R.string.i04_card_block))
        }
    }
    if (offer.canMarkRead) {
        IconButton(onClick = { onDone(); spam.onMarkRead(chosen) }) {
            Icon(Symbols.DoneAll, contentDescription = stringResource(R.string.convlist_mark_read))
        }
    }
}

/** Title, and an overflow menu with "Mark all read" (when given) and "Empty …" while the folder holds chats. */
@Composable
private fun FolderTopBar(
    folder: ConversationFolder,
    onBack: (() -> Unit)?,
    hasRows: Boolean,
    onMarkAllRead: (() -> Unit)?,
    onEmpty: () -> Unit,
    more: List<FolderMenuEntry> = emptyList(),
) {
    var menuOpen by remember { mutableStateOf(false) }
    val emptying = folder.emptying.takeIf { hasRows }
    val markAllRead = onMarkAllRead.takeIf { hasRows }
    UMessageTopBar(title = stringResource(folder.titleRes), onBack = onBack) {
        // A labelled menu instead of a lone trash icon that looked like "clear all" (CL-31).
        if (emptying != null || markAllRead != null || more.isNotEmpty()) {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Symbols.MoreVert, contentDescription = stringResource(R.string.nav_folder_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    more.forEach { entry ->
                        DropdownMenuItem(
                            text = { Text(stringResource(entry.labelRes)) },
                            leadingIcon = { Icon(entry.icon, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                entry.onClick()
                            },
                        )
                    }
                    markAllRead?.let { markAllRead ->
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.nav_folder_mark_all_read)) },
                            leadingIcon = { Icon(Symbols.DoneAll, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                markAllRead()
                            },
                        )
                    }
                    emptying?.let {
                        DropdownMenuItem(
                            text = { Text(stringResource(it.menuRes)) },
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
}

/** The empty folder; for Spam with its strip ([intro]: the filter is off, older spam) kept above. */
@Composable
private fun BoxScope.FolderEmpty(
    folder: ConversationFolder,
    onBackToMessages: (() -> Unit)?,
    intro: (@Composable () -> Unit)?,
) {
    val empty = @Composable { modifier: Modifier ->
        EmptyState(
            icon = when (folder) {
                ConversationFolder.Archive -> Symbols.Archive
                ConversationFolder.RecycleBin -> Symbols.Delete
                ConversationFolder.Spam -> Symbols.Shield
            },
            title = stringResource(folder.emptyTitleRes),
            body = stringResource(folder.emptyBodyRes),
            modifier = modifier,
            // P3-03: the Archived tab stays while it is open; leaving is the user's choice.
            action = onBackToMessages?.let { back ->
                { Button(onClick = back) { Text(stringResource(R.string.nav_archived_back)) } }
            },
        )
    }
    if (intro == null) {
        empty(Modifier.align(Alignment.Center))
        return
    }
    Column(Modifier.fillMaxSize()) {
        intro()
        Box(Modifier.weight(1f).fillMaxWidth()) { empty(Modifier.align(Alignment.Center)) }
    }
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
    spamSwipe: ((ConversationRow) -> SwipeAction?)? = null,
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
            if (spamSwipe != null) {
                // Spam: one direction only, "Not spam" or "Unblock" by the row's cause, with Undo (4.2).
                SwipeableConversation(
                    row = row,
                    selected = row.threadId in selection,
                    selecting = selecting,
                    canArchive = false,
                    callbacks = callbacks,
                    startToEnd = spamSwipe(row),
                    modifier = Modifier.animateItem(),
                )
            } else if (folder == ConversationFolder.Archive) {
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
    binCounts: BinMessageCounts?,
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
        if (folder == ConversationFolder.Spam) {
            SpamDeleteDialog(ids, onDismiss, onClearSelection, onDelete)
        } else {
            DeleteDialog(ids, binCounts, onDismiss, onClearSelection, onDelete)
        }
    }
    val restore = folder.restore
    if (confirmRestore != null && restore != null) {
        val items = pluralStringResource(R.plurals.delete_conversations, confirmRestore.size, confirmRestore.size)
        ConfirmDialog(
            title = stringResource(restore.titleRes),
            message = stringResource(restore.messageRes, items),
            confirmLabel = stringResource(restore.labelRes),
            onConfirm = {
                onDismiss()
                onClearSelection()
                onRestore(confirmRestore)
            },
            onDismiss = onDismiss,
        )
    }
    val emptying = folder.emptying
    if (confirmEmpty && emptying != null) {
        val count = if (binCounts != null) {
            pluralStringResource(R.plurals.delete_messages, binCounts.total, binCounts.total)
        } else {
            pluralStringResource(R.plurals.delete_conversations, rowCount, rowCount)
        }
        ConfirmDialog(
            title = stringResource(emptying.titleRes),
            message = stringResource(emptying.messageRes) + " " +
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

/** Delete for good: whole chats, or on the Recycle Bin screen the binned messages of each chat. */
@Composable
private fun DeleteDialog(
    ids: Set<Long>,
    binCounts: BinMessageCounts?,
    onDismiss: () -> Unit,
    onClearSelection: () -> Unit,
    onDelete: (Set<Long>) -> Unit,
) {
    val binned = binCounts?.let { counts -> ids.sumOf { counts.perChat[it] ?: 0 } }
    val items = if (binned != null) {
        pluralStringResource(R.plurals.delete_messages, binned, binned)
    } else {
        pluralStringResource(R.plurals.delete_conversations, ids.size, ids.size)
    }
    ConfirmDialog(
        title = stringResource(R.string.screens_delete_forever_title),
        message = stringResource(
            if (binned != null) {
                R.string.screens_delete_bin_messages_message
            } else {
                R.string.screens_delete_conversations_message
            },
            items,
        ),
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

/**
 * U2 (answer 17): Delete in Spam says that the texts go from the phone itself, for every app; the Undo window of the
 * snackbar follows, after which nothing can bring them back.
 */
@Composable
private fun SpamDeleteDialog(
    ids: Set<Long>,
    onDismiss: () -> Unit,
    onClearSelection: () -> Unit,
    onDelete: (Set<Long>) -> Unit,
) {
    ConfirmDialog(
        title = pluralStringResource(R.plurals.pt_spam_delete_title, ids.size, ids.size),
        message = stringResource(R.string.pt_spam_delete_body),
        confirmLabel = stringResource(R.string.spam_action_delete),
        destructive = true,
        onConfirm = {
            onDismiss()
            onClearSelection()
            onDelete(ids)
        },
        onDismiss = onDismiss,
    )
}
