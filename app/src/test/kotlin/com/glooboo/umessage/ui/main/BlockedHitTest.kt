package com.glooboo.umessage.ui.main

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** main-22: the spam section of the main search. */
class BlockedHitTest {

    private val hits = listOf(
        BlockedHit("+15550001234", "+15550001234", "free again", "t3000", 2),
        BlockedHit("BANK-X", "BANK-X", "Your free code", "t2000", 1),
    )

    @Test
    fun theSectionIsOnlyShownForTypedTextWithoutATile() {
        val state = MainSearchState()
        state.blockedHits = hits
        assertThat(state.shownBlockedHits(typed = true)).hasSize(2)
        assertThat(state.shownBlockedHits(typed = false)).isEmpty()
        state.filter = InboxFilter.Unread
        assertThat(state.shownBlockedHits(typed = true)).isEmpty()
        assertThat(noHits(emptyList(), emptyList(), emptyList(), hits)).isFalse()
    }
}
