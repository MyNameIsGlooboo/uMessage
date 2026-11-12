package com.glooboo.umessage.ui.main

import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** main-22: the spam section of the main search. */
class BlockedHitTest {

    private fun msg(id: Long, address: String, body: String, at: Long) =
        BlockedMessage(id, address, body, 1, false, at, DecisionReason.UNKNOWN_SENDER)

    private val held = listOf(
        msg(1, "+14155550133", "Win a free prize now", 1000),
        msg(2, "+14155550133", "free again", 3000),
        msg(3, "BANK-X", "Your free code", 2000),
        msg(4, "+442071838750", "Meeting moved", 4000),
    )

    private fun hits(query: String, hidden: Set<String> = emptySet()) = buildBlockedHits(
        held, query, keyOf = { it }, hiddenSenderKeys = hidden, title = { it }, formatDate = { "t$it" },
    )

    @Test
    fun oneHitPerSenderNewestFirstWithTheMatchCount() {
        val found = hits("free")
        assertThat(found.map { it.address }).containsExactly("+14155550133", "BANK-X").inOrder()
        assertThat(found.first().count).isEqualTo(2)
        assertThat(found.first().snippet).isEqualTo("free again")
        assertThat(found.first().date).isEqualTo("t3000")
    }

    @Test
    fun aSenderWithAChatHiddenBehindTheLockIsNotFound() {
        assertThat(hits("free", hidden = setOf("+14155550133")).map { it.address }).containsExactly("BANK-X")
    }

    @Test
    fun nothingMatchingGivesNoHits() {
        assertThat(hits("zzz")).isEmpty()
    }

    @Test
    fun theSectionIsOnlyShownForTypedTextWithoutATile() {
        val state = MainSearchState()
        state.blockedHits = hits("free")
        assertThat(state.shownBlockedHits(typed = true)).hasSize(2)
        assertThat(state.shownBlockedHits(typed = false)).isEmpty()
        state.filter = InboxFilter.Unread
        assertThat(state.shownBlockedHits(typed = true)).isEmpty()
        assertThat(noHits(emptyList(), emptyList(), emptyList(), hits("free"))).isFalse()
    }
}
