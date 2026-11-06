package com.glooboo.umessage.ui.thread

import com.glooboo.umessage.models.ConversationLabel
import com.glooboo.umessage.models.Label
import com.glooboo.umessage.ui.labels.LabelsUiState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThreadLabelChipRulesTest {

    private val labels = (1L..5L).map { Label(id = it, name = "L$it", color = 0, sortOrder = it.toInt()) }

    private fun state(vararg links: Pair<Long, Long>) =
        LabelsUiState.of(labels, links.map { ConversationLabel(threadId = it.first, labelId = it.second) })

    @Test
    fun aConversationShowsOnlyItsOwnLabels() {
        val chips = ThreadLabelChipRules.chipsFor(state(7L to 2L, 8L to 3L), threadId = 7L)
        assertThat(chips.shown.map { it.id }).containsExactly(2L)
        assertThat(chips.hidden).isEqualTo(0)
    }

    @Test
    fun moreThanThreeLabelsAreCounted() {
        val all = state(7L to 1L, 7L to 2L, 7L to 3L, 7L to 4L, 7L to 5L)
        val chips = ThreadLabelChipRules.chipsFor(all, threadId = 7L)
        assertThat(chips.shown.map { it.id }).containsExactly(1L, 2L, 3L).inOrder()
        assertThat(chips.hidden).isEqualTo(2)
    }

    @Test
    fun noLabelsOrNoThreadShowNothing() {
        assertThat(ThreadLabelChipRules.chipsFor(state(), threadId = 7L).isEmpty).isTrue()
        assertThat(ThreadLabelChipRules.chipsFor(state(0L to 1L), threadId = 0L).isEmpty).isTrue()
    }

    private val widths = listOf(40, 50, 60)

    @Test
    fun allChipsShowWhenTheyFit() {
        assertThat(ThreadLabelChipRules.fitCount(widths, 0, 160, 4) { 20 }).isEqualTo(3)
    }

    @Test
    fun chipsThatDoNotFitAreCountedInPlusN() {
        // 40 + 4 + 50 = 94, then the "+n" chip: 4 + 20 = 118
        assertThat(ThreadLabelChipRules.fitCount(widths, 0, 120, 4) { 20 }).isEqualTo(2)
        assertThat(ThreadLabelChipRules.fitCount(widths, 2, 160, 4) { 20 }).isEqualTo(2)
    }

    @Test
    fun onlyPlusNWhenNoChipFitsAndNothingWhenEvenThatDoesNot() {
        assertThat(ThreadLabelChipRules.fitCount(widths, 0, 30, 4) { 20 }).isEqualTo(0)
        assertThat(ThreadLabelChipRules.fitCount(widths, 0, 10, 4) { 20 }).isNull()
    }
}
