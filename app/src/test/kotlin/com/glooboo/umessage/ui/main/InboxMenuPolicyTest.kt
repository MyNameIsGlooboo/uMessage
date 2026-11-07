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
    ) = InboxMenuInputs(archiveAvailable, archived, bin, scheduled, tabShown)

    @Test
    fun anEmptyInboxOffersOnlyStarredAndSettings() {
        assertEquals(
            listOf(InboxMenuItem.Starred, InboxMenuItem.Settings),
            InboxMenuPolicy.entries(inputs()),
        )
    }

    @Test
    fun archivedShowsOnlyWithArchivedChats() {
        assertEquals(InboxMenuItem.Archived, InboxMenuPolicy.entries(inputs(archived = 2)).first())
        assertEquals(1, InboxMenuPolicy.entries(inputs(archived = 2, bin = true)).indexOf(InboxMenuItem.Archived))
        assertFalse(InboxMenuItem.Archived in InboxMenuPolicy.entries(inputs(archived = 0)))
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
    fun nothingArchivedMeansNoArchivedEntryWhateverTheTab() {
        assertFalse(InboxMenuItem.Archived in InboxMenuPolicy.entries(inputs(archived = 0, tabShown = false)))
        assertFalse(InboxMenuItem.Archived in InboxMenuPolicy.entries(inputs(archived = 0, tabShown = true)))
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
