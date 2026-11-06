package com.glooboo.umessage.ui.main

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.MessageCategory
import com.glooboo.umessage.models.SearchResult
import com.glooboo.umessage.ui.conversations.ConversationRow
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InboxFilterPolicyTest {

    private fun row(
        id: Long,
        unread: Boolean = false,
        snippet: String = "hi",
        category: MessageCategory = MessageCategory.Other,
    ) = ConversationRow(
        threadId = id, title = "T$id", snippet = snippet, dateLabel = "", unread = unread, unreadBadge = "",
        unreadCount = 0, pinned = false, muted = false, hasDraft = false, scheduled = false, simSlot = null,
        photoUri = "", isGroup = false, phoneNumber = "", category = category,
    )

    private fun hit(threadId: Long) =
        SearchResult(messageId = 1, title = "", snippet = "", date = "", threadId = threadId, photoUri = "")

    private val rows = listOf(
        row(1, unread = true),
        row(2, snippet = "Your verification code is 482913"),
        row(3, category = MessageCategory.Transactions),
        row(4, category = MessageCategory.Offers),
        row(5),
    )

    private fun ids(filter: InboxFilter) = InboxFilterPolicy.filterRows(rows, filter).map { it.threadId }

    @Test
    fun noFilterKeepsEveryRow() {
        assertThat(ids(InboxFilter.All)).containsExactly(1L, 2L, 3L, 4L, 5L).inOrder()
    }

    @Test
    fun eachFilterKeepsItsOwnRows() {
        assertThat(ids(InboxFilter.Unread)).containsExactly(1L)
        assertThat(ids(InboxFilter.Codes)).containsExactly(2L)
        assertThat(ids(InboxFilter.Transactions)).containsExactly(3L)
        assertThat(ids(InboxFilter.Offers)).containsExactly(4L)
    }

    @Test
    fun aFreshCodeCountsEvenWhenTheSnippetHasNone() {
        val fresh = row(9).copy(otpCode = "4821")
        assertThat(InboxFilterPolicy.matches(InboxFilter.Codes, fresh)).isTrue()
    }

    @Test
    fun tappingATileTurnsItOnAndTappingItAgainTurnsItOff() {
        val on = InboxFilterPolicy.afterTap(InboxFilter.All, InboxFilter.Unread)
        assertThat(on).isEqualTo(InboxFilter.Unread)
        assertThat(InboxFilterPolicy.afterTap(on, InboxFilter.Unread)).isEqualTo(InboxFilter.All)
    }

    @Test
    fun tappingAnotherTileSwitchesTheFilter() {
        assertThat(InboxFilterPolicy.afterTap(InboxFilter.Unread, InboxFilter.Codes)).isEqualTo(InboxFilter.Codes)
    }

    @Test
    fun theChipShowsOnlyWhileAFilterIsOnAndNothingIsSelected() {
        assertThat(InboxFilterPolicy.chipVisible(InboxFilter.All, selecting = false)).isFalse()
        assertThat(InboxFilterPolicy.chipVisible(InboxFilter.Unread, selecting = false)).isTrue()
        assertThat(InboxFilterPolicy.chipVisible(InboxFilter.Offers, selecting = false)).isTrue()
        assertThat(InboxFilterPolicy.chipVisible(InboxFilter.Unread, selecting = true)).isFalse()
    }

    @Test
    fun typedResultsAreNarrowedToTheFilter() {
        val results = listOf(hit(1), hit(2), hit(3))
        val unread = InboxFilterPolicy.filterRows(rows, InboxFilter.Unread)
        assertThat(InboxFilterPolicy.narrow(results, InboxFilter.Unread, unread).map { it.threadId })
            .containsExactly(1L)
        assertThat(InboxFilterPolicy.narrow(results, InboxFilter.All, rows)).isEqualTo(results)
    }

    @Test
    fun aLabelKeepsTheConversationsItIsGiven() {
        val kept = InboxFilterPolicy.filterRows(rows, InboxFilter.Label, setOf(2L, 5L))
        assertThat(kept.map { it.threadId }).containsExactly(2L, 5L).inOrder()
        assertThat(InboxFilterPolicy.filterRows(rows, InboxFilter.Label)).isEmpty()
    }

    @Test
    fun everyTileHasAFilterOfItsOwn() {
        val filters = searchFilterTiles.map { it.filter }
        assertThat(filters).containsNoDuplicates()
        assertThat(filters).doesNotContain(InboxFilter.All)
        val withoutTile = setOf(InboxFilter.All, InboxFilter.Label, InboxFilter.Sim)
        assertThat(filters).containsExactlyElementsIn(InboxFilter.entries - withoutTile)
    }
}
