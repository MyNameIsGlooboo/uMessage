package com.glooboo.umessage.ui.spam

import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.engine.NumberNormalizer
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** I20: one number written in several forms is one row on the Blocked tab, one chip count, one thread. */
class SenderGrouping042Test {

    private val keys = SenderKeys(NumberNormalizer { "GB" })

    private fun held(id: Long, address: String, at: Long) = BlockedMessage(
        id = id, address = address, body = "msg $id", subscriptionId = 1, isMms = false, receivedAt = at,
        reason = DecisionReason.UNKNOWN_SENDER,
    )

    @Test fun theSameNumberInThreeFormsIsOneRowNamedByTheNewestMessage() {
        val groups = groupBySender(
            listOf(held(1, "+447700900123", 1), held(2, "07700900123", 2), held(3, "00447700900123", 3)),
            keys::keyOf,
        )

        assertThat(groups).hasSize(1)
        assertThat(groups.single().address).isEqualTo("00447700900123")
        assertThat(groups.single().messages.map { it.id }).containsExactly(3L, 2L, 1L).inOrder()
    }

    @Test fun aThreadOpenedWithAnyFormOfTheNumberStillFindsItsRow() {
        val group = groupBySender(
            listOf(held(1, "+447700900123", 1), held(2, "07700900123", 2)),
            keys::keyOf,
        ).single()

        assertThat(group.hasAddress("+447700900123")).isTrue()
        assertThat(group.hasAddress("07700900123")).isTrue()
        assertThat(group.hasAddress("+447700900124")).isFalse()
    }

    @Test fun differentNumbersAndDifferentCountriesStayApart() {
        val groups = groupBySender(
            listOf(held(1, "+447700900123", 1), held(2, "+447700900124", 2), held(3, "+917700900123", 3)),
            keys::keyOf,
        )

        assertThat(groups).hasSize(3)
    }

    @Test fun senderNamesAreOneRowWhateverTheCase() {
        val groups = groupBySender(listOf(held(1, "MyBank", 1), held(2, "MYBANK", 2)), keys::keyOf)

        assertThat(groups).hasSize(1)
    }

    @Test fun withoutAKeyFunctionEveryAddressIsItsOwnRow() {
        val groups = groupBySender(listOf(held(1, "+447700900123", 1), held(2, "07700900123", 2)))

        assertThat(groups).hasSize(2)
    }

    @Test fun aValueThatIsNotANumberKeepsItsOwnText() {
        assertThat(keys.keyOf("")).isEqualTo("")
        assertThat(keys.keyOf("12")).isNotEmpty()
    }

    @Test fun chipCountsCountSendersNotWrittenForms() {
        val messages = listOf(held(1, "+447700900123", 1), held(2, "07700900123", 2), held(3, "+447700900124", 3))

        val counts = BlockedFilters.counts(messages, emptyList(), keys::keyOf)

        assertThat(counts[BlockedFilter.All]).isEqualTo(2)
        assertThat(BlockedFilters.counts(messages, emptyList())[BlockedFilter.All]).isEqualTo(3)
    }
}
