package com.glooboo.umessage.ui.conversations

import com.glooboo.umessage.R
import com.glooboo.umessage.ui.components.TabMenuItem
import com.glooboo.umessage.ui.components.tabMenuItems
import com.glooboo.umessage.ui.icons.Symbols

/**
 * The rows of a folder's overflow menu: the folder's own rows first (its [more] entries, "Mark all read" when given,
 * "Empty …" while it holds chats), then Settings last on a main tab. A pure list so the order can be tested.
 */
internal fun folderMenuItems(
    more: List<FolderMenuEntry>,
    markAllRead: (() -> Unit)?,
    emptying: FolderEmptying?,
    onEmpty: () -> Unit,
    onSettings: (() -> Unit)?,
): List<TabMenuItem> {
    val entries = buildList {
        more.forEach { add(TabMenuItem(it.labelRes, it.icon, it.onClick)) }
        markAllRead?.let { add(TabMenuItem(R.string.nav_folder_mark_all_read, Symbols.DoneAll, it)) }
        emptying?.let { add(TabMenuItem(it.menuRes, Symbols.DeleteSweep, onEmpty)) }
    }
    return tabMenuItems(entries, onSettings)
}
