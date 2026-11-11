package com.glooboo.umessage.ui.spam

import androidx.compose.runtime.Immutable

@Immutable
class BlockedActions(
    /** Null on the Blocked tab: no back arrow. */
    val onBack: (() -> Unit)? = null,
    val onOpenRules: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    /** Opens the sender's read-only thread (owner notes r3): a row no longer expands in place. */
    val onOpenSender: (String) -> Unit = {},
    val onToggleSelected: (String) -> Unit = {},
    val onClearSelection: () -> Unit = {},
    val onSelectAll: () -> Unit = {},
    val onNotSpam: (Set<String>) -> Unit = {},
    val onDelete: (Set<String>) -> Unit = {},
    /** "Delete all" of the menu, after its confirmation: also clears the restored list (spamui-15). */
    val onDeleteAll: () -> Unit = {},
    /** A chip under the banner (ID-14). */
    val onSetFilter: (BlockedFilter) -> Unit = {},
    /** I32: sends the one selected sender's newest message to the carrier; asked and confirmed by the screen first. */
    val onReportSpam: (Set<String>) -> Unit = {},
)
