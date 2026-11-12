package com.glooboo.umessage.ui.spam

import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** spamui-20: the search box of the Blocked screen filters the sender rows by sender and text. */
class BlockedSearchTest {

    private fun msg(id: Long, address: String, body: String, at: Long = 1000L + id) =
        BlockedMessage(id, address, body, 1, false, at, DecisionReason.UNKNOWN_SENDER)

    private val all = listOf(
        msg(1, "+14155550133", "Win a free prize"),
        msg(2, "+14155550133", "Second note"),
        msg(3, "BANK-X", "Your code is 482913"),
        msg(4, "+442071838750", "Meeting moved"),
    )

    @Test
    fun aBlankQueryMatchesEverything() {
        assertThat(all.all { BlockedFilters.matchesQuery(it, "") }).isTrue()
        assertThat(all.all { BlockedFilters.matchesQuery(it, "   ") }).isTrue()
    }

    @Test
    fun textIsMatchedWithoutRegardToCase() {
        assertThat(all.filter { BlockedFilters.matchesQuery(it, "FREE PRIZE") }.map { it.id }).containsExactly(1L)
    }

    @Test
    fun theSenderIsMatchedByNameOrByNumberInAnyWrittenForm() {
        assertThat(all.filter { BlockedFilters.matchesQuery(it, "bank") }.map { it.id }).containsExactly(3L)
        assertThat(all.filter { BlockedFilters.matchesQuery(it, "415 555") }.map { it.id }).containsExactly(1L, 2L)
        assertThat(all.filter { BlockedFilters.matchesQuery(it, "(207)183") }.map { it.id }).containsExactly(4L)
    }

    @Test
    fun persianLetterFormsAndDigitsAreFolded() {
        val persian = msg(5, "+989121234567", "کد تایید 1234")
        assertThat(BlockedFilters.matchesQuery(persian, "تاييد")).isTrue()
        assertThat(BlockedFilters.matchesQuery(persian, "۱۲۳۴")).isTrue()
    }

    /** A sender stays when one message matches and keeps ALL its messages, so Not spam still moves the whole sender. */
    @Test
    fun aSenderRowKeepsAllItsMessages() {
        val groups = groupBySender(all)
        val found = BlockedFilters.searchGroups(groups, "free prize")
        assertThat(found.map { it.address }).containsExactly("+14155550133")
        assertThat(found.single().messages.map { it.id }).containsExactly(1L, 2L)
        assertThat(BlockedFilters.searchGroups(groups, "nothing like this")).isEmpty()
        assertThat(BlockedFilters.searchGroups(groups, "")).isEqualTo(groups)
    }
}
