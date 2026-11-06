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
    /** The answer to the "Always allow?" prompt after Not spam: token and whether to allow. */
    val onAnswerAllow: (Long, Boolean) -> Unit = { _, _ -> },
    /** I22: the "Also allow messages like this" box of that prompt. */
    val onIncludeSimilar: (Long, Boolean) -> Unit = { _, _ -> },
    /** A chip under the banner (ID-14). */
    val onSetFilter: (BlockedFilter) -> Unit = {},
    /** I32: sends the one selected sender's newest message to the carrier; asked and confirmed by the screen first. */
    val onReportSpam: (Set<String>) -> Unit = {},
)
