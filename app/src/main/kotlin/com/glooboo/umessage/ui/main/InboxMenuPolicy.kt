package com.glooboo.umessage.ui.main

/** The entries of the inbox's overflow menu, in the order shown; Settings is always last. */
internal enum class InboxMenuItem { Archived, RecycleBin, Scheduled, Starred, Settings }

/** What the menu needs to know to decide which entries have something behind them. */
internal data class InboxMenuInputs(
    val archiveAvailable: Boolean,
    val archivedCount: Int,
    val recycleBinOn: Boolean,
    val scheduledCount: Int,
    /** The Archived tab is in the bottom bar, so the menu must not list it a second time. */
    val archivedTabShown: Boolean = false,
)

/**
 * U04: Archived, Recycle bin and Scheduled are reachable from the inbox, each only when it has content or is on.
 * Archived is listed only while its bottom-bar tab is not shown: an entry never appears in both places.
 * Settings is always the last entry, so the menu is never empty.
 */
internal object InboxMenuPolicy {

    fun entries(inputs: InboxMenuInputs): List<InboxMenuItem> = buildList {
        if (!inputs.archivedTabShown && inputs.archiveAvailable && inputs.archivedCount > 0) {
            add(InboxMenuItem.Archived)
        }
        if (inputs.recycleBinOn) add(InboxMenuItem.RecycleBin)
        if (inputs.scheduledCount > 0) add(InboxMenuItem.Scheduled)
        add(InboxMenuItem.Starred)
        add(InboxMenuItem.Settings)
    }
}
