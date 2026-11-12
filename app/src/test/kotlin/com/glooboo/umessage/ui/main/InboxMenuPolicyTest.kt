package com.glooboo.umessage.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class InboxMenuPolicyTest {

    private fun inputs(
        archiveAvailable: Boolean = true,
        archived: Int = 0,
        bin: Boolean = false,
        scheduled: Int = 0,
        tabShown: Boolean = false,
        unread: Int = 0,
    ) = InboxMenuInputs(archiveAvailable, archived, bin, scheduled, tabShown, unread)

    /** main-21: Mark all read is the first entry, and only while some chat is unread. */
    @Test
    fun markAllReadIsFirstAndOnlyWhileSomethingIsUnread() {
        assertFalse(InboxMenuItem.MarkAllRead in InboxMenuPolicy.entries(inputs(bin = true, unread = 0)))
        val entries = InboxMenuPolicy.entries(inputs(bin = true, archived = 2, unread = 3))
        assertEquals(InboxMenuItem.MarkAllRead, entries.first())
        assertEquals(InboxMenuItem.Settings, entries.last())
        assertEquals(1, entries.count { it == InboxMenuItem.MarkAllRead })
    }

    @Test
    fun anEmptyInboxWithTheArchivedTabOffersOnlyStarredAndSettings() {
        assertEquals(
            listOf(InboxMenuItem.Starred, InboxMenuItem.Settings),
            InboxMenuPolicy.entries(inputs(tabShown = true)),
        )
    }

    @Test
    fun archivedIsListedWhenItsTabIsNotShown() {
        assertEquals(InboxMenuItem.Archived, InboxMenuPolicy.entries(inputs(archived = 2)).first())
        assertEquals(1, InboxMenuPolicy.entries(inputs(archived = 2, bin = true)).indexOf(InboxMenuItem.Archived))
        assertEquals(true, InboxMenuItem.Archived in InboxMenuPolicy.entries(inputs(archived = 0)))
    }

    @Test
    fun archivedIsLeftOutOnAPhoneWithoutArchiving() {
        assertFalse(InboxMenuItem.Archived in InboxMenuPolicy.entries(inputs(archiveAvailable = false, archived = 3)))
    }

    @Test
    fun archivedIsLeftOutWhileItsTabIsInTheBottomBar() {
        val entries = InboxMenuPolicy.entries(inputs(archived = 5, tabShown = true))
        assertFalse(InboxMenuItem.Archived in entries)
        assertEquals(listOf(InboxMenuItem.Starred, InboxMenuItem.Settings), entries)
    }

    @Test
    fun archivedIsOfferedWhenItsTabIsHiddenButChatsAreArchived() {
        assertEquals(true, InboxMenuItem.Archived in InboxMenuPolicy.entries(inputs(archived = 5, tabShown = false)))
    }

    @Test
    fun theArchivedEntryIsNeverInBothPlacesAndNeverInNeither() {
        val tabs = MainTabInputs(archiveAvailable = true, archivedCount = 0, forwardRuleCount = 0, showForwarded = null)
        val tabShown = MainTab.Archived in MainTabPolicy.visibleTabs(tabs, MainTab.Messages)
        val entry = InboxMenuItem.Archived in InboxMenuPolicy.entries(inputs(archived = 0, tabShown = tabShown))
        assertEquals(true, tabShown != entry)
    }

    @Test
    fun otherEntriesStayWhenTheArchivedTabIsShown() {
        assertEquals(
            listOf(
                InboxMenuItem.RecycleBin,
                InboxMenuItem.Scheduled,
                InboxMenuItem.Starred,
                InboxMenuItem.Settings,
            ),
            InboxMenuPolicy.entries(inputs(archived = 2, bin = true, scheduled = 3, tabShown = true)),
        )
    }

    @Test
    fun settingsIsAlwaysTheLastEntry() {
        assertEquals(InboxMenuItem.Settings, InboxMenuPolicy.entries(inputs()).last())
        val entries = InboxMenuPolicy.entries(inputs(archived = 1, bin = true, scheduled = 2))
        assertEquals(InboxMenuItem.Settings, entries.last())
    }

    @Test
    fun recycleBinFollowsTheSetting() {
        assertEquals(true, InboxMenuItem.RecycleBin in InboxMenuPolicy.entries(inputs(bin = true)))
        assertFalse(InboxMenuItem.RecycleBin in InboxMenuPolicy.entries(inputs(bin = false)))
    }

    @Test
    fun scheduledShowsOnlyWhileSomethingIsScheduled() {
        assertEquals(true, InboxMenuItem.Scheduled in InboxMenuPolicy.entries(inputs(scheduled = 1)))
        assertFalse(InboxMenuItem.Scheduled in InboxMenuPolicy.entries(inputs(scheduled = 0)))
    }

    @Test
    fun allEntriesComeInTheFixedOrder() {
        assertEquals(
            listOf(
                InboxMenuItem.RecycleBin,
                InboxMenuItem.Archived,
                InboxMenuItem.Scheduled,
                InboxMenuItem.Starred,
                InboxMenuItem.Settings,
            ),
            InboxMenuPolicy.entries(inputs(archived = 1, bin = true, scheduled = 4)),
        )
    }

    @Test
    fun recycleBinIsFirstWheneverItIsOnEvenWhenEmpty() {
        assertEquals(InboxMenuItem.RecycleBin, InboxMenuPolicy.entries(inputs(bin = true)).first())
        assertEquals(
            InboxMenuItem.RecycleBin,
            InboxMenuPolicy.entries(inputs(archived = 3, bin = true, scheduled = 2)).first(),
        )
    }

    @Test
    fun recycleBinShowsItsCountOnlyWhenNotEmpty() {
        assertEquals(3, InboxMenuPolicy.recycleBinCountToShow(3))
        assertEquals(null, InboxMenuPolicy.recycleBinCountToShow(0))
        assertEquals(null, InboxMenuPolicy.recycleBinCountToShow(-1))
    }
}
