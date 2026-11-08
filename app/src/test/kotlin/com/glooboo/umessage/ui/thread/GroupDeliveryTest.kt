package com.glooboo.umessage.ui.thread

import android.provider.Telephony.Sms
import com.glooboo.umessage.models.Message
import com.google.common.truth.Truth.assertThat
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.junit.Test

/** Q12: each person's own delivery state in a group message, derived from the rows the SMS provider holds. */
class GroupDeliveryTest {

    private val ann = GroupPerson("111", "Ann")
    private val bob = GroupPerson("222", "Bob")
    private val cy = GroupPerson("333", "Cy")
    private val people = listOf(ann, bob, cy)
    private val sentSummary = CopyRow(Sms.MESSAGE_TYPE_SENT, Sms.STATUS_NONE, dateMillis = 1_000L)

    private fun copy(
        type: Int = Sms.MESSAGE_TYPE_SENT,
        status: Int = Sms.STATUS_NONE,
        date: Long = 2_000L,
        sent: Long = 0L,
    ) = CopyRow(type, status, date, sent)

    private fun stateOf(details: DeliveryDetails, person: GroupPerson) =
        details.recipients.single { it.person == person }.state

    private fun details(
        copies: Map<String, CopyRow>,
        summary: CopyRow = sentSummary,
        reportsOn: Boolean = true,
        isMms: Boolean = false,
    ) = groupDeliveryDetails(people, summary, isMms, copies, reportsOn)

    // copyState

    @Test
    fun aCopyThatWentOutWithoutAReportIsSent() {
        assertThat(copyState(copy(), deliveryReportsOn = true)).isEqualTo(RecipientState.Sent)
        assertThat(copyState(copy(status = Sms.STATUS_PENDING), true)).isEqualTo(RecipientState.Sent)
    }

    @Test
    fun aCompleteReportIsDeliveredOnlyWhileReportsAreOn() {
        assertThat(copyState(copy(status = Sms.STATUS_COMPLETE), true)).isEqualTo(RecipientState.Delivered)
        assertThat(copyState(copy(status = Sms.STATUS_COMPLETE), false)).isEqualTo(RecipientState.Sent)
    }

    @Test
    fun aFailureReportIsNotDeliveredAndARadioFailureIsNotSent() {
        assertThat(copyState(copy(status = Sms.STATUS_FAILED), true)).isEqualTo(RecipientState.NotDelivered)
        assertThat(copyState(copy(status = Sms.STATUS_FAILED + 5), true)).isEqualTo(RecipientState.NotDelivered)
        assertThat(copyState(copy(type = Sms.MESSAGE_TYPE_FAILED), true)).isEqualTo(RecipientState.NotSent)
    }

    @Test
    fun aCopyStillInTheOutboxIsPending() {
        assertThat(copyState(copy(type = Sms.MESSAGE_TYPE_OUTBOX), true)).isEqualTo(RecipientState.Pending)
        assertThat(copyState(copy(type = Sms.MESSAGE_TYPE_QUEUED), true)).isEqualTo(RecipientState.Pending)
    }

    // groupDeliveryDetails

    @Test
    fun everybodyDelivered() {
        val all = people.associate { it.address to copy(status = Sms.STATUS_COMPLETE, sent = 3_000L) }
        val result = details(all)
        assertThat(result.perPerson).isTrue()
        assertThat(result.recipients.map { it.state }).containsExactly(
            RecipientState.Delivered, RecipientState.Delivered, RecipientState.Delivered,
        )
        assertThat(result.recipients.map { it.timeMillis }.distinct()).containsExactly(3_000L)
    }

    @Test
    fun oneFailedOneDeliveredOnePending() {
        val result = details(
            mapOf(
                "111" to copy(type = Sms.MESSAGE_TYPE_FAILED),
                "222" to copy(status = Sms.STATUS_COMPLETE, sent = 4_000L),
                "333" to copy(type = Sms.MESSAGE_TYPE_OUTBOX),
            ),
            // The failed copy turned the group row failed (MS1); each person still shows their own state.
            summary = CopyRow(Sms.MESSAGE_TYPE_FAILED, Sms.STATUS_NONE, 1_000L),
        )
        assertThat(stateOf(result, ann)).isEqualTo(RecipientState.NotSent)
        assertThat(stateOf(result, bob)).isEqualTo(RecipientState.Delivered)
        assertThat(stateOf(result, cy)).isEqualTo(RecipientState.Pending)
        assertThat(result.recipients.single { it.person == cy }.timeMillis).isNull()
        assertThat(result.recipients.single { it.person == bob }.timeMillis).isEqualTo(4_000L)
        assertThat(result.recipients.single { it.person == ann }.timeMillis).isEqualTo(2_000L)
    }

    @Test
    fun partialReportsShowSentForTheOnesWithoutAReport() {
        val result = details(
            mapOf("111" to copy(status = Sms.STATUS_COMPLETE, sent = 5_000L), "222" to copy(), "333" to copy()),
        )
        assertThat(result.recipients.map { it.state })
            .containsExactly(RecipientState.Delivered, RecipientState.Sent, RecipientState.Sent).inOrder()
    }

    @Test
    fun aDeliveryReportOfFailureOnOneCopyOnly() {
        val result = details(
            mapOf(
                "111" to copy(status = Sms.STATUS_FAILED, sent = 6_000L),
                "222" to copy(status = Sms.STATUS_COMPLETE),
                "333" to copy(),
            ),
        )
        assertThat(stateOf(result, ann)).isEqualTo(RecipientState.NotDelivered)
        assertThat(result.recipients.single { it.person == ann }.timeMillis).isEqualTo(6_000L)
        assertThat(stateOf(result, bob)).isEqualTo(RecipientState.Delivered)
        // No report time yet: the time the copy was sent.
        assertThat(result.recipients.single { it.person == bob }.timeMillis).isEqualTo(2_000L)
        assertThat(stateOf(result, cy)).isEqualTo(RecipientState.Sent)
    }

    @Test
    fun withReportsOffNobodyIsDelivered() {
        val all = people.associate { it.address to copy(status = Sms.STATUS_COMPLETE) }
        assertThat(details(all, reportsOn = false).recipients.map { it.state }.distinct())
            .containsExactly(RecipientState.Sent)
    }

    @Test
    fun aPersonWithoutACopyShowsTheOverallState() {
        val result = details(mapOf("111" to copy(status = Sms.STATUS_COMPLETE)))
        assertThat(stateOf(result, ann)).isEqualTo(RecipientState.Delivered)
        assertThat(stateOf(result, bob)).isEqualTo(RecipientState.Sent)
        assertThat(result.recipients.single { it.person == bob }.timeMillis).isEqualTo(1_000L)

        val failed = details(emptyMap(), summary = CopyRow(Sms.MESSAGE_TYPE_FAILED, Sms.STATUS_NONE, 1_000L))
        assertThat(failed.recipients.map { it.state }.distinct()).containsExactly(RecipientState.NotSent)
    }

    @Test
    fun aGroupPictureMessageHasOneStateForEverybody() {
        // Its status column is empty and reads as 0, which is "complete" for an SMS: still not a delivery.
        val summary = CopyRow(Sms.MESSAGE_TYPE_SENT, Sms.STATUS_COMPLETE, 1_000L)
        val result = details(emptyMap(), summary = summary, isMms = true)
        assertThat(result.perPerson).isFalse()
        assertThat(result.recipients.map { it.state }.distinct()).containsExactly(RecipientState.Sent)
        assertThat(result.recipients).hasSize(3)
    }

    @Test
    fun aGroupPictureMessageIgnoresCopyRowsOfTheSameAddress() {
        val result = details(
            mapOf("111" to copy(status = Sms.STATUS_COMPLETE)),
            summary = CopyRow(Sms.MESSAGE_TYPE_FAILED, Sms.STATUS_NONE, 1_000L),
            isMms = true,
        )
        assertThat(result.recipients.map { it.state }.distinct()).containsExactly(RecipientState.NotSent)
    }

    @Test
    fun theSameAddressTwiceCountsOnce() {
        val twice = listOf(ann, ann.copy(name = "Ann again"), bob)
        val result = groupDeliveryDetails(twice, sentSummary, false, emptyMap(), true)
        assertThat(result.recipients.map { it.person.address }).containsExactly("111", "222").inOrder()
    }

    // groupPeopleOf

    private fun contact(name: String, number: String, normalized: String = number) = SimpleContact(
        0, 0, name, "", arrayListOf(PhoneNumber(number, 0, "", normalized)), ArrayList(), ArrayList(),
    )

    private fun groupMessage(vararg contacts: SimpleContact) = Message(
        id = 1, body = "hi", type = Sms.MESSAGE_TYPE_SENT, status = Sms.STATUS_NONE,
        participants = arrayListOf(*contacts), date = 100, read = true, threadId = 1, isMMS = false, attachment = null,
        senderPhoneNumber = "111|222", senderName = "", senderPhotoUri = "", subscriptionId = -1,
    )

    @Test
    fun thePeopleOfAMessageAreItsDifferentAddressesWithTheirNames() {
        val message = groupMessage(
            contact("Ann", "0911", normalized = "+98911"), contact("", "222"), contact("Ann twin", "0911", "+98911"),
        )
        assertThat(groupPeopleOf(message)).containsExactly(
            GroupPerson("+98911", "Ann"), GroupPerson("222", "222"),
        ).inOrder()
    }
}
