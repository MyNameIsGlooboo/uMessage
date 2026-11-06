package com.glooboo.umessage.ui.conversations

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.models.ConversationLabel
import com.glooboo.umessage.models.Label
import com.glooboo.umessage.ui.labels.LabelColor
import com.glooboo.umessage.ui.labels.LabelsUiState
import com.glooboo.umessage.ui.spam.SimInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RowLabelsTest {

    private fun label(n: Int) = RowLabel("L$n", n)

    @Test
    fun twoLabelsShowWholeAndMoreFoldIntoACount() {
        assertThat(labelChips(emptyList()).shown).isEmpty()
        assertThat(labelChips(listOf(label(1))).more).isEqualTo(0)
        val two = labelChips(listOf(label(1), label(2)))
        assertThat(two.shown).hasSize(2)
        assertThat(two.more).isEqualTo(0)
        val five = labelChips((1..5).map(::label))
        assertThat(five.shown.map { it.name }).containsExactly("L1", "L2").inOrder()
        assertThat(five.more).isEqualTo(3)
    }

    @Test
    fun labelsComeInTheOrderOfTheLabelList() {
        val labels = listOf(Label(1, "Work", 10, 0), Label(2, "Bank", 20, 1))
        val links = listOf(ConversationLabel(5, 2), ConversationLabel(5, 1), ConversationLabel(6, 2))
        val byThread = labelsByThread(LabelsUiState.of(labels, links))
        assertThat(byThread[5]!!.map { it.name }).containsExactly("Work", "Bank").inOrder()
        assertThat(byThread[6]!!.map { it.name }).containsExactly("Bank")
        assertThat(byThread[7]).isNull()
    }

    @Test
    fun everyLabelColourHasReadableText() {
        LabelColor.entries.forEach { colour ->
            assertThat(contrastRatio(colour.color, readableOn(colour.color))).isAtLeast(4.5)
        }
        assertThat(readableOn(0xFFF9A825.toInt())).isEqualTo(0x000000)
        assertThat(readableOn(0xFF1A237E.toInt())).isEqualTo(0xFFFFFF)
    }

    @Test
    fun theBadgeMarksOnlyTheSimThatIsNotTheDefault() {
        val sims = listOf(SimInfo(11, 1, "A"), SimInfo(12, 2, "B"))
        assertThat(SimBadge.slotToShow(11, sims, defaultSubscriptionId = 11)).isNull()
        assertThat(SimBadge.slotToShow(12, sims, defaultSubscriptionId = 11)).isEqualTo(2)
        assertThat(SimBadge.slotToShow(0, sims, defaultSubscriptionId = 11)).isNull()
        assertThat(SimBadge.slotToShow(99, sims, defaultSubscriptionId = 11)).isNull()
    }

    @Test
    fun theAppDefaultWinsOverThePhoneDefault() {
        assertThat(SimBadge.defaultSubscription(appDefault = 12, systemDefault = 11)).isEqualTo(12)
        assertThat(SimBadge.defaultSubscription(appDefault = -2, systemDefault = 11)).isEqualTo(11)
        assertThat(SimBadge.defaultSubscription(appDefault = -3, systemDefault = 11)).isEqualTo(11)
    }

    @Test
    fun aSimIsNamedByItsNicknameElseBySlot() {
        assertThat(SimBadge.nameOf("Work", "SIM 2")).isEqualTo("Work")
        assertThat(SimBadge.nameOf("  ", "SIM 2")).isEqualTo("SIM 2")
        assertThat(SimBadge.nameOf(null, "SIM 2")).isEqualTo("SIM 2")
    }

    @Test
    fun aSimWithoutNicknameIsNamedByItsCarrier() {
        assertThat(SimBadge.nameOf("Work", "Vodafone", "SIM 2")).isEqualTo("Work")
        assertThat(SimBadge.nameOf(null, "  Vodafone ", "SIM 2")).isEqualTo("Vodafone")
        assertThat(SimBadge.nameOf(" ", "Vodafone", "SIM 2")).isEqualTo("Vodafone")
    }

    @Test
    fun aBlankOrPlaceholderCarrierFallsBackToTheSlot() {
        listOf(null, "", "  ", "Unknown", "No service", "NO SIM").forEach {
            assertThat(SimBadge.nameOf(null, it, "SIM 2")).isEqualTo("SIM 2")
        }
    }
}
