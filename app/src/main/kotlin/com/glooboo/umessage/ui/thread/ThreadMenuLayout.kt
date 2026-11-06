package com.glooboo.umessage.ui.thread

/** The sections of the "More..." sheet, in the order they show. [Danger] has no title. */
enum class ThreadMoreGroup { ShareExport, Organize, Sender, Danger }

data class ThreadMoreSection(val group: ThreadMoreGroup, val actions: List<ThreadMenuAction>)

/**
 * How the top bar menu is laid out: the [top] entries, then "More..." when [more] has anything, so the menu never
 * lists more than [MAX_ENTRIES] rows.
 */
data class ThreadMenuLayout(val top: List<ThreadMenuAction>, val more: List<ThreadMoreSection>) {
    val hasMore: Boolean get() = more.isNotEmpty()

    companion object {
        const val MAX_ENTRIES = 6
    }
}

/**
 * Sorts the entries that apply ([menu]) into the short list and the "More..." sheet. The short list holds what is used
 * most: details, search, mute, archive and block. The sheet groups the rest: share and export, organise, the sender,
 * and Delete last. In the recycle bin there is nothing to group: Restore and Delete stand in the list.
 */
fun layoutThreadMenu(menu: ThreadMenuState): ThreadMenuLayout {
    if (menu.restore) {
        return ThreadMenuLayout(
            top = pick(menu.restore to ThreadMenuAction.Restore, menu.delete to ThreadMenuAction.Delete),
            more = emptyList(),
        )
    }
    val top = pick(
        menu.details to ThreadMenuAction.Details,
        menu.search to ThreadMenuAction.Search,
        menu.mute to ThreadMenuAction.Mute,
        menu.unmute to ThreadMenuAction.Unmute,
        menu.archive to ThreadMenuAction.Archive,
        menu.unarchive to ThreadMenuAction.Unarchive,
        menu.unblock to ThreadMenuAction.Unblock,
        (menu.block && !menu.unblock) to ThreadMenuAction.Block,
    )
    val more = listOf(
        ThreadMoreSection(
            ThreadMoreGroup.ShareExport,
            pick(
                menu.shareAsText to ThreadMenuAction.ShareAsText,
                menu.exportConversation to ThreadMenuAction.ExportConversation,
                menu.addToHomeScreen to ThreadMenuAction.AddToHomeScreen,
            ),
        ),
        ThreadMoreSection(
            ThreadMoreGroup.Organize,
            pick(
                menu.labels to ThreadMenuAction.Labels,
                menu.rename to ThreadMenuAction.Rename,
                menu.hide to ThreadMenuAction.Hide,
                menu.unhide to ThreadMenuAction.Unhide,
                menu.markUnread to ThreadMenuAction.MarkUnread,
            ),
        ),
        ThreadMoreSection(
            ThreadMoreGroup.Sender,
            pick(
                // A rule that names the sender without blocking all of it offers both: Unblock leads the list.
                (menu.block && menu.unblock) to ThreadMenuAction.Block,
                menu.copyNumber to ThreadMenuAction.CopyNumber,
                menu.addToContact to ThreadMenuAction.AddToContact,
                menu.allowSender to ThreadMenuAction.AllowSender,
                menu.whyDecided to ThreadMenuAction.WhyDecided,
                menu.reportSpam to ThreadMenuAction.ReportSpam,
                menu.alwaysForward to ThreadMenuAction.AlwaysForward,
                menu.managePeople to ThreadMenuAction.ManagePeople,
            ),
        ),
        ThreadMoreSection(ThreadMoreGroup.Danger, pick(menu.delete to ThreadMenuAction.Delete)),
    ).filter { it.actions.isNotEmpty() }
    return ThreadMenuLayout(top, more)
}

private fun pick(vararg entries: Pair<Boolean, ThreadMenuAction>): List<ThreadMenuAction> =
    entries.filter { it.first }.map { it.second }
