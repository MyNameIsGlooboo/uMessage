package com.glooboo.umessage.ui.main

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.models.SearchResult
import com.glooboo.umessage.ui.conversations.ConversationRow
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchPageModelTest {

    private fun row(id: Long) = ConversationRow(
        threadId = id, title = "Chat $id", snippet = "last $id", dateLabel = "Mon", unread = id == 2L,
        unreadBadge = "3", unreadCount = 3, pinned = false, muted = false, hasDraft = false, scheduled = false,
        simSlot = null, photoUri = "", isGroup = false, phoneNumber = "",
    )

    private fun hit(messageId: Long, threadId: Long, snippet: String = "s") = SearchResult(
        messageId = messageId, title = "t$threadId", snippet = snippet, date = "d", threadId = threadId, photoUri = "",
    )

    @Test
    fun messagesOfOneThreadBecomeOneRow() {
        val hits = listOf(hit(10, 1, "first"), hit(11, 2), hit(12, 1, "second"))
        val rows = conversationHits(hits, listOf(row(1), row(2)))
        assertThat(rows.map { it.threadId }).containsExactly(1L, 2L).inOrder()
        assertThat(rows[0].snippet).isEqualTo("first")
        assertThat(rows[0].open.messageId).isEqualTo(10)
        assertThat(rows[1].row?.unreadBadge).isEqualTo("3")
    }

    @Test
    fun aMessageHitBeatsATitleHitOfTheSameThreadButKeepsItsPlace() {
        val rows = conversationHits(
            listOf(hit(CONVERSATION_HIT, 5, "+44 712"), hit(CONVERSATION_HIT, 6), hit(40, 5, "code is 1234")),
            listOf(row(5), row(6)),
        )
        assertThat(rows.map { it.threadId }).containsExactly(5L, 6L).inOrder()
        assertThat(rows[0].snippet).isEqualTo("code is 1234")
        assertThat(rows[1].open.messageId).isEqualTo(CONVERSATION_HIT)
    }

    @Test
    fun aConversationOutsideTheInboxKeepsItsHitText() {
        val rows = conversationHits(listOf(hit(1, 9, "old text")), listOf(row(1)))
        assertThat(rows.single().row).isNull()
        assertThat(rows.single().title).isEqualTo("t9")
    }

    @Test
    fun aTileWithoutTextListsEachConversationWithItsLastMessage() {
        val rows = conversationRows(listOf(row(1), row(2)))
        assertThat(rows.map { it.snippet }).containsExactly("last 1", "last 2").inOrder()
        assertThat(rows[0].open.messageId).isEqualTo(CONVERSATION_HIT)
        assertThat(rows[0].date).isEqualTo("Mon")
    }

    @Test
    fun theFirstTwentyShowAndTheRestFoldAway() {
        val all = (1..45).toList()
        val first = resultPage(all)
        assertThat(first.items).hasSize(RESULTS_FIRST)
        assertThat(first.hidden).isEqualTo(25)
        val everything = resultPage(all, Int.MAX_VALUE)
        assertThat(everything.items).hasSize(45)
        assertThat(everything.hidden).isEqualTo(0)
        assertThat(resultPage(listOf(1, 2)).hidden).isEqualTo(0)
    }

    @Test
    fun theMatchInASnippetIsHighlightedWhereverItSits() {
        val text = "Your verification code is 482913, code expires"
        val ranges = highlightRanges(text, "CODE")
        assertThat(ranges.map { text.substring(it) }).containsExactly("code", "code").inOrder()
    }

    @Test
    fun onlyFiveRecentSearchesShow() {
        assertThat(shownRecent(listOf("a", "b", "c", "d", "e", "f", "g"))).containsExactly("a", "b", "c", "d", "e")
        assertThat(shownRecent(emptyList())).isEmpty()
    }

    @Test
    fun theResultsShowWhileATileIsOnOrTextIsTyped() {
        val search = MainSearchState()
        assertThat(search.showsResults()).isFalse()
        search.query = "a"
        assertThat(search.showsResults()).isFalse()
        search.query = "ab"
        assertThat(search.showsResults()).isTrue()
        search.query = ""
        search.filter = InboxFilter.Codes
        assertThat(search.showsResults()).isTrue()
    }

    @Test
    fun backFromTheResultsClearsTileAndText() {
        val search = MainSearchState()
        search.filter = InboxFilter.Unread
        var typed: String? = null
        search.backToTiles { typed = it }
        assertThat(search.filter).isEqualTo(InboxFilter.All)
        assertThat(typed).isEmpty()
    }

    @Test
    fun aSimTileTurnsOnAndASecondTapTurnsItOff() {
        val search = MainSearchState()
        search.toggleSim(11)
        assertThat(search.filter).isEqualTo(InboxFilter.Sim)
        assertThat(search.simId).isEqualTo(11)
        search.toggleSim(12)
        assertThat(search.simId).isEqualTo(12)
        search.toggleSim(12)
        assertThat(search.filter).isEqualTo(InboxFilter.All)
        assertThat(search.simId).isEqualTo(NO_SIM)
    }
}
