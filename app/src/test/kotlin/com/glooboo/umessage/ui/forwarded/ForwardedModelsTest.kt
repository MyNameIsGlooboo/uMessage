package com.glooboo.umessage.ui.forwarded

import com.glooboo.umessage.spam.ForwardLogEntry
import com.glooboo.umessage.spam.ForwardStatus
import com.glooboo.umessage.ui.spam.SimInfo
import com.google.common.truth.Truth.assertThat
import java.time.ZoneId
import org.junit.Test

class ForwardedModelsTest {

    private val utc = ZoneId.of("UTC")
    private val day = 24L * 60 * 60 * 1000

    private fun entry(id: Long, at: Long, status: ForwardStatus = ForwardStatus.SENT) =
        ForwardLogEntry(id, at, at, 1, "BankX", at, false, "+15550001111", null, status, null, 1)

    @Test
    fun entriesAreGroupedByDayInOrder() {
        val groups = groupByDay(listOf(entry(3, 2 * day + 5), entry(2, 2 * day + 1), entry(1, day)), utc)
        assertThat(groups.map { it.entries.map { e -> e.id } }).containsExactly(listOf(3L, 2L), listOf(1L)).inOrder()
    }

    @Test
    fun simSlotShowsOnlyOnDualSim() {
        val sims = listOf(SimInfo(11, 1, "A"), SimInfo(12, 2, "B"))
        assertThat(simSlotOf(sims, 12)).isEqualTo(2)
        assertThat(simSlotOf(sims, null)).isNull()
        assertThat(simSlotOf(sims.take(1), 11)).isNull()
    }

    @Test
    fun failedAndPaidNoteFollowTheEntries() {
        val state =
            ForwardedUiState(entries = listOf(entry(2, 5, ForwardStatus.FAILED), entry(1, 4, ForwardStatus.PENDING)))
        assertThat(state.failed.map { it.id }).containsExactly(2L)
        assertThat(state.showPaidNote).isTrue()
        assertThat(state.copy(entries = listOf(entry(1, 4))).showPaidNote).isFalse()
    }

    @Test
    fun theTabIsOfferedOnlyForTheFirstRuleOnce() {
        assertThat(ForwardedTabPrompt.shouldOffer(0, null)).isTrue()
        assertThat(ForwardedTabPrompt.shouldOffer(1, null)).isFalse()
        assertThat(ForwardedTabPrompt.shouldOffer(0, false)).isFalse()
    }
}
