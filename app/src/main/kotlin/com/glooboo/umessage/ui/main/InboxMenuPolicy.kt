package com.glooboo.umessage.ui.main

/** The entries of the inbox's overflow menu, in the order shown; Settings is always last. */
internal enum class InboxMenuItem { MarkAllRead, RecycleBin, Archived, Scheduled, Starred, Settings }

/** What the menu needs to know to decide which entries have something behind them. */
internal data class InboxMenuInputs(
    val archiveAvailable: Boolean,
    val archivedCount: Int,
    val recycleBinOn: Boolean,
    val scheduledCount: Int,
    /** The Archived tab is in the bottom bar, so the menu must not list it a second time. */
    val archivedTabShown: Boolean = false,
    /** Unread chats in the list; "Mark all read" is offered only while there are some. */
    val unreadCount: Int = 0,
)

/**
 * U04: Recycle bin (first), Archived and Scheduled are reachable from the inbox, each only when it has content
 * or is on.
 * Archived is listed only while its bottom-bar tab is not shown: an entry never appears in both places. The tab is
 * shown whenever archiving is available, so this entry is only a fallback and never leaves Archived unreachable.
 * Settings is always the last entry, so the menu is never empty.
 */
internal object InboxMenuPolicy {

    fun entries(inputs: InboxMenuInputs): List<InboxMenuItem> = buildList {
        // Mark all read is first: it acts on the list the user is looking at.
        if (inputs.unreadCount > 0) add(InboxMenuItem.MarkAllRead)
        // The bin comes first so it is easy to find; it is listed whenever it is switched on, empty or not.
        if (inputs.recycleBinOn) add(InboxMenuItem.RecycleBin)
        if (!inputs.archivedTabShown && inputs.archiveAvailable) {
            add(InboxMenuItem.Archived)
        }
        if (inputs.scheduledCount > 0) add(InboxMenuItem.Scheduled)
        add(InboxMenuItem.Starred)
        add(InboxMenuItem.Settings)
    }

    /** The number the Recycle bin entry shows, or null for an empty bin: it then shows just its name. */
    fun recycleBinCountToShow(count: Int): Int? = count.takeIf { it > 0 }
}
