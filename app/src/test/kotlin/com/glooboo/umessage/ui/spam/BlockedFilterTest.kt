package com.glooboo.umessage.ui.spam

import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** ID-14: the filter chips of the Blocked tab. */
class BlockedFilterTest {

    private fun msg(id: Long, body: String, sim: Int, reason: DecisionReason) =
        BlockedMessage(id, "sender$id", body, sim, false, 1000L + id, reason)

    private val all = listOf(
        msg(1, "Win a prize at http://x.example", 1, DecisionReason.UNKNOWN_SENDER),
        msg(2, "Your code is 482913", 2, DecisionReason.UNKNOWN_SENDER),
        msg(3, "cheap pills", 2, DecisionReason.BLOCK_RULE),
    )

    @Test
    fun eachChipNarrowsTheList() {
        assertThat(BlockedFilters.apply(BlockedFilter.All, all)).hasSize(3)
        assertThat(BlockedFilters.apply(BlockedFilter.UnknownSender, all).map { it.id }).containsExactly(1L, 2L)
        assertThat(BlockedFilters.apply(BlockedFilter.MyRules, all).map { it.id }).containsExactly(3L)
        assertThat(BlockedFilters.apply(BlockedFilter.HasLink, all).map { it.id }).containsExactly(1L)
        assertThat(BlockedFilters.apply(BlockedFilter.Sim(2), all).map { it.id }).containsExactly(2L, 3L)
    }

    @Test
    fun countsMatchTheChips() {
        val counts = BlockedFilters.counts(all, listOf(1, 2))
        assertThat(counts[BlockedFilter.All]).isEqualTo(3)
        assertThat(counts[BlockedFilter.UnknownSender]).isEqualTo(2)
        assertThat(counts[BlockedFilter.HasLink]).isEqualTo(1)
        assertThat(counts[BlockedFilter.Sim(1)]).isEqualTo(1)
        assertThat(counts[BlockedFilter.Sim(2)]).isEqualTo(2)
    }

    /** Round 4: the list has one row per sender, so the chips count senders, not messages ("All 4" over 3 rows). */
    @Test
    fun countsAreSendersNotMessages() {
        val twice = all + BlockedMessage(4, "sender2", "Second message", 1, false, 2000L, DecisionReason.UNKNOWN_SENDER)
        val counts = BlockedFilters.counts(twice, listOf(1, 2))
        assertThat(counts[BlockedFilter.All]).isEqualTo(groupBySender(twice).size)
        assertThat(counts[BlockedFilter.All]).isEqualTo(3)
        assertThat(counts[BlockedFilter.UnknownSender]).isEqualTo(2)
        assertThat(counts[BlockedFilter.MyRules]).isEqualTo(1)
        // sender2 has a message on each SIM, so both SIM chips list them.
        assertThat(counts[BlockedFilter.Sim(1)]).isEqualTo(2)
        assertThat(counts[BlockedFilter.Sim(2)]).isEqualTo(2)
        listOf(BlockedFilter.All, BlockedFilter.UnknownSender, BlockedFilter.Sim(1)).forEach { filter ->
            assertThat(counts[filter]).isEqualTo(groupBySender(BlockedFilters.apply(filter, twice)).size)
        }
    }

    @Test
    fun selectionRoundTripsAndUnreadableMeansAll() {
        listOf(
            BlockedFilter.All, BlockedFilter.UnknownSender, BlockedFilter.MyRules, BlockedFilter.HasLink,
            BlockedFilter.Sim(5),
        ).forEach { assertThat(BlockedFilters.decode(BlockedFilters.encode(it))).isEqualTo(it) }
        assertThat(BlockedFilters.decode(null)).isEqualTo(BlockedFilter.All)
        assertThat(BlockedFilters.decode("sim:x")).isEqualTo(BlockedFilter.All)
        assertThat(BlockedFilters.decode("???")).isEqualTo(BlockedFilter.All)
    }

    /** U31: a chip that lists what All lists is left out, and a row with nothing to narrow is not shown at all. */
    @Test
    fun chipsAreShownOnlyWhenTheyNarrowTheList() {
        val one = BlockedFilters.counts(listOf(all[0], msg(4, "hello", 1, DecisionReason.UNKNOWN_SENDER)), listOf(1))
        // Unknown sender 2 = All 2 and SIM 1 = All 2: nothing to choose between.
        assertThat(BlockedFilters.chipShown(BlockedFilter.UnknownSender, BlockedFilter.All, one)).isFalse()
        assertThat(BlockedFilters.chipShown(BlockedFilter.Sim(1), BlockedFilter.All, one)).isFalse()
        assertThat(BlockedFilters.chipShown(BlockedFilter.HasLink, BlockedFilter.All, one)).isTrue()
        assertThat(BlockedFilters.rowShown(BlockedFilter.All, one)).isTrue()

        val same = BlockedFilters.counts(listOf(msg(1, "hi", 1, DecisionReason.UNKNOWN_SENDER)), listOf(1))
        assertThat(BlockedFilters.rowShown(BlockedFilter.All, same)).isFalse()
        // A filter left on from an earlier visit keeps its chip and the row, so it can be switched off.
        assertThat(BlockedFilters.rowShown(BlockedFilter.HasLink, same)).isTrue()
        assertThat(BlockedFilters.chipShown(BlockedFilter.HasLink, BlockedFilter.HasLink, same)).isTrue()
    }
}
