package com.glooboo.umessage.ui.main

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.ui.conversations.ConversationRow
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SimFilterTest {

    private fun row(id: Long) = ConversationRow(
        threadId = id, title = "", snippet = "", dateLabel = "", unread = false, unreadBadge = "", unreadCount = 0,
        pinned = false, muted = false, hasDraft = false, scheduled = false, simSlot = null, photoUri = "",
        isGroup = false, phoneNumber = "",
    )

    @Test
    fun noTileOnAPhoneWithOneSim() {
        assertThat(SimFilter.tilesFor(listOf(7), { it }, { "SIM 1" })).isEmpty()
        assertThat(SimFilter.tilesFor(emptyList<Int>(), { it }, { "SIM 1" })).isEmpty()
    }

    @Test
    fun oneTilePerSimWithTwo() {
        val tiles = SimFilter.tilesFor(listOf(7, 9), { it }, { "SIM for $it" })
        assertThat(tiles).containsExactly(SimTile(7, "SIM for 7", 7), SimTile(9, "SIM for 9", 9)).inOrder()
    }

    @Test
    fun simsWithTheSameNameGetTheirSlotAdded() {
        val tiles = SimFilter.tilesFor(listOf(7, 9), { it }, { "Vodafone" }, slot = { it - 6 })
        assertThat(tiles).containsExactly(SimTile(7, "Vodafone 1", 1), SimTile(9, "Vodafone 3", 3)).inOrder()
        assertThat(SimFilter.withSlotsWhenAlike(listOf("Vodafone", "vodafone"), listOf(1, 2)))
            .containsExactly("Vodafone 1", "vodafone 2").inOrder()
    }

    @Test
    fun simsWithDifferentNamesKeepThem() {
        val tiles = SimFilter.tilesFor(listOf(7, 9), { it }, { if (it == 7) "Vodafone" else "Orange" })
        assertThat(tiles).containsExactly(SimTile(7, "Vodafone", 7), SimTile(9, "Orange", 9)).inOrder()
    }

    @Test
    fun theTileKeepsItsSlotForThePadWhateverTheCaption() {
        val named = SimFilter.tilesFor(listOf(7, 9), { it }, { if (it == 7) "Home" else "Work" }, slot = { it - 6 })
        assertThat(named.map { it.slot }).containsExactly(1, 3).inOrder()
        assertThat(named.map { it.label }).containsExactly("Home", "Work").inOrder()
        val alike = SimFilter.tilesFor(listOf(7, 9), { it }, { "Orange" }, slot = { it - 6 })
        assertThat(alike.map { it.slot }).containsExactly(1, 3).inOrder()
    }

    @Test
    fun aThreadIsOnTheSimWhenAMessageOrItsNewestMessageUsedIt() {
        val stored = listOf(1L, 2L)
        val newest = mapOf(2L to 7, 3L to 7, 4L to 9)
        assertThat(SimFilter.threadsOnSim(stored, newest, 7)).containsExactly(1L, 2L, 3L)
        assertThat(SimFilter.threadsOnSim(emptyList(), newest, 9)).containsExactly(4L)
        assertThat(SimFilter.threadsOnSim(emptyList(), emptyMap(), 9)).isEmpty()
    }

    @Test
    fun tappingTheTileThatIsOnTurnsItOff() {
        assertThat(SimFilter.afterTap(NO_SIM, 7)).isEqualTo(7)
        assertThat(SimFilter.afterTap(7, 9)).isEqualTo(9)
        assertThat(SimFilter.afterTap(7, 7)).isEqualTo(NO_SIM)
    }

    @Test
    fun theSimFilterKeepsTheThreadsItIsGiven() {
        val rows = listOf(1L, 2L, 3L).map(::row)
        val kept = InboxFilterPolicy.filterRows(rows, InboxFilter.Sim, setOf(2L, 3L))
        assertThat(kept.map { it.threadId }).containsExactly(2L, 3L)
    }
}
