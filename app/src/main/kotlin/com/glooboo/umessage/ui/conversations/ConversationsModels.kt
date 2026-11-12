package com.glooboo.umessage.ui.conversations

import androidx.compose.runtime.Immutable
import com.glooboo.umessage.helpers.MessageCategory

/** One list row, fully precomputed off the main thread so composing a row does no work. */
@Immutable
data class ConversationRow(
    val threadId: Long,
    val title: String,
    val snippet: String,
    val dateLabel: String,
    val unread: Boolean,
    /** Empty when there is nothing to show; "99+" above 99. */
    val unreadBadge: String,
    val unreadCount: Int,
    val pinned: Boolean,
    val muted: Boolean,
    val hasDraft: Boolean,
    val scheduled: Boolean,
    /** 1-based SIM slot, only set when the phone has 2+ active SIMs and the thread has a remembered SIM. */
    val simSlot: Int?,
    val photoUri: String,
    val isGroup: Boolean,
    val phoneNumber: String,
    /** CL-11: the one-time code of a fresh unread message, for the "Copy" chip; null when there is none. */
    val otpCode: String? = null,
    /** I11: what the latest message looks like, for the Transactions and Offers filters. */
    val category: MessageCategory = MessageCategory.Other,
    /** The SIM badge's text: the nickname the user gave the SIM; empty shows "SIM n" ([simSlot]). */
    val simName: String = "",
    /** The chat has at least one private note on a message: a small note mark shows in the row. */
    val hasNotes: Boolean = false,
    /** A saved contact (a name, not a bare number): the block swipe asks first. Never set for a group. */
    val isContact: Boolean = false,
)

/** What the contextual selection bar may offer, derived from the current selection. */
@Immutable
data class SelectionActions(
    val count: Int = 0,
    val canPin: Boolean = false,
    val canUnpin: Boolean = false,
    val canMarkRead: Boolean = false,
    val canMarkUnread: Boolean = false,
    val canArchive: Boolean = false,
    /** "Report spam" blocks one sender per chat, so it is offered only when no group chat is selected. */
    val canReportSpam: Boolean = false,
    /** "Block number" skips group chats, so it is offered only when at least one single-sender chat is selected. */
    val canBlock: Boolean = false,
    val canAddContact: Boolean = false,
    val canCopyNumber: Boolean = false,
    val canDial: Boolean = false,
    val canRename: Boolean = false,
    val canShowDetails: Boolean = false,
    val allSelected: Boolean = false,
    /** Distinct phone numbers of the selection, for the block confirmation. */
    val numbers: List<String> = emptyList(),
    /** Current title of the single selected conversation, prefilled in the rename dialog. */
    val renameInitial: String = "",
)

@Immutable
data class ConversationsUiState(
    /** True until the first list arrived. */
    val loading: Boolean = true,
    /** First-run import: empty cache while telephony is read; show progress instead of the empty state. */
    val importing: Boolean = false,
    val rows: List<ConversationRow> = emptyList(),
    val selected: Set<Long> = emptySet(),
    val selection: SelectionActions = SelectionActions(),
    /** Messages blocked since the Blocked tab was last shown; drives the tab badge. */
    val newBlockedCount: Int = 0,
    val archiveAvailable: Boolean = true,
    /** B06: chats hidden behind the app lock (0 while no app lock is on); drives the "Hidden chats" line. */
    val hiddenChats: Int = 0,
    /** B06: the hidden chats are unlocked, so they show in [rows]. */
    val hiddenUnlocked: Boolean = false,
    /** Chats waiting for the user to confirm their deletion; 0 shows no dialog. */
    val confirmDeleteCount: Int = 0,
) {
    val selecting: Boolean get() = selected.isNotEmpty()
}

/** One-shot results the screen turns into snackbars. */
sealed interface ConversationsEvent {
    data class Deleted(val token: Long, val count: Int) : ConversationsEvent
    data class Archived(val token: Long, val count: Int) : ConversationsEvent

    /** "Mark all read" read [count] chats; Undo makes exactly those chats unread again ([token]). */
    data class MarkedAllRead(val token: Long, val count: Int) : ConversationsEvent
    /** Block rules were added for [count] conversations' senders. Offers Undo ([token] as for [ReportedSpam]). */
    data class Blocked(val token: Long, val count: Int) : ConversationsEvent

    /** "Report spam" blocked [count] senders and, when [archived], archived their chats. Offers Undo. */
    data class ReportedSpam(val token: Long, val count: Int, val archived: Boolean) : ConversationsEvent
    data object Failed : ConversationsEvent
}

/** One-shot requests only an Activity can fulfil (permissions, other screens). */
sealed interface ConversationsEffect {
    data class Dial(val number: String) : ConversationsEffect
    data class AddToContact(val number: String) : ConversationsEffect
    data class Details(val threadId: Long) : ConversationsEffect
}

/** Everything the screen can ask for, so composables hold no logic and previews can pass [None]. */
class ConversationsCallbacks(
    val onOpen: (ConversationRow) -> Unit = {},
    val onToggleSelect: (Long) -> Unit = {},
    val onStartSelect: (Long) -> Unit = {},
    val onClearSelection: () -> Unit = {},
    val onSelectAll: () -> Unit = {},
    val onPin: (Boolean) -> Unit = {},
    val onMarkRead: (Boolean) -> Unit = {},
    /** The inbox menu's "Mark all read" (main-21), and the snackbar's Undo for it. */
    val onMarkAllRead: () -> Unit = {},
    val onUndoMarkAllRead: (Long) -> Unit = {},
    val onArchiveSelected: () -> Unit = {},
    val onDeleteSelected: () -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
    val onCancelDelete: () -> Unit = {},
    val onArchiveOne: (Long) -> Unit = {},
    val onDeleteOne: (Long) -> Unit = {},
    val onBlockSelected: () -> Unit = {},
    /** The block swipe on one row (CL-12). */
    val onBlockOne: (Long) -> Unit = {},
    val onReportSpamSelected: () -> Unit = {},
    val onUndoReportSpam: (Long) -> Unit = {},
    val onCommitReportSpam: (Long) -> Unit = {},
    val onAddContact: () -> Unit = {},
    val onCopyNumber: () -> Unit = {},
    val onDial: () -> Unit = {},
    val onRename: (String) -> Unit = {},
    val onDetails: () -> Unit = {},
    val onUndoDelete: (Long) -> Unit = {},
    val onCommitDelete: (Long) -> Unit = {},
    val onUndoArchive: (Long) -> Unit = {},
    val onNewConversation: () -> Unit = {},
) {
    companion object {
        val None = ConversationsCallbacks()
    }
}
