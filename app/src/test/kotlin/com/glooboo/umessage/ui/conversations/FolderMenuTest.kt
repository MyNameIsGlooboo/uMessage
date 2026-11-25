package com.glooboo.umessage.ui.conversations

import com.glooboo.umessage.R
import com.glooboo.umessage.ui.components.tabMenuItems
import com.glooboo.umessage.ui.icons.Symbols
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderMenuTest {
    private val spamRows = listOf(
        FolderMenuEntry(R.string.spam_blocked_open_settings, Symbols.Settings) {},
        FolderMenuEntry(R.string.settings_spam_open_rules, Symbols.Rule) {},
    )
    private val archiveEmpty = FolderEmptying(
        R.string.inbox_empty_archive_menu,
        R.string.screens_empty_archive_title,
        R.string.empty_archive_confirmation,
    )

    @Test
    fun `the Spam menu keeps its own rows first and ends with Settings`() {
        val labels = folderMenuItems(spamRows, markAllRead = {}, emptying = null, onEmpty = {}, onSettings = {})
            .map { it.labelRes }
        assertEquals(
            listOf(
                R.string.spam_blocked_open_settings,
                R.string.settings_spam_open_rules,
                R.string.nav_folder_mark_all_read,
                R.string.main_settings,
            ),
            labels,
        )
    }

    @Test
    fun `the Archived menu keeps Mark all read and Empty before Settings`() {
        val labels = folderMenuItems(
            more = emptyList(),
            markAllRead = {},
            emptying = archiveEmpty,
            onEmpty = {},
            onSettings = {},
        ).map { it.labelRes }
        assertEquals(
            listOf(
                R.string.nav_folder_mark_all_read,
                R.string.inbox_empty_archive_menu,
                R.string.main_settings,
            ),
            labels,
        )
    }

    @Test
    fun `the Forwarded menu is Settings alone`() {
        assertEquals(
            listOf(R.string.main_settings),
            tabMenuItems(emptyList(), onSettings = {}).map { it.labelRes },
        )
    }

    @Test
    fun `without Settings the folder menu keeps its old rows`() {
        assertEquals(
            listOf(R.string.nav_folder_mark_all_read),
            folderMenuItems(emptyList(), markAllRead = {}, emptying = null, onEmpty = {}, onSettings = null)
                .map { it.labelRes },
        )
        assertEquals(
            R.string.main_settings,
            folderMenuItems(emptyList(), markAllRead = null, emptying = archiveEmpty, onEmpty = {}, onSettings = {})
                .last().labelRes,
        )
    }
}
