package com.glooboo.umessage.ui.thread

import android.content.ContentValues
import android.content.Context
import android.provider.Telephony.Sms
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.testing.FakeSmsMmsProvider
import com.google.common.truth.Truth.assertThat
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Q12: the per-person states come from the rows of the SMS provider; nothing is stored for them. */
@RunWith(AndroidJUnit4::class)
class GroupDeliveryReaderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val groupMillis = 1_000_000L

    @Before
    fun setUp() {
        FakeSmsMmsProvider.installSms()
    }

    private fun row(
        address: String,
        type: Int = Sms.MESSAGE_TYPE_SENT,
        status: Int = Sms.STATUS_NONE,
        date: Long = groupMillis,
        dateSent: Long = 0L,
        body: String = "Dinner at 8?",
    ) {
        context.contentResolver.insert(
            Sms.CONTENT_URI,
            ContentValues().apply {
                put(Sms.ADDRESS, address)
                put(Sms.BODY, body)
                put(Sms.DATE, date)
                put(Sms.DATE_SENT, dateSent)
                put(Sms.TYPE, type)
                put(Sms.STATUS, status)
            },
        )
    }

    private fun contact(number: String) = SimpleContact(
        0, 0, "Name $number", "", arrayListOf(PhoneNumber(number, 0, "", number)), ArrayList(), ArrayList(),
    )

    private fun groupMessage(isMms: Boolean = false) = Message(
        id = 9, body = "Dinner at 8?", type = Sms.MESSAGE_TYPE_SENT, status = Sms.STATUS_NONE,
        participants = arrayListOf(contact("111"), contact("222"), contact("333")),
        date = (groupMillis / 1000).toInt(), read = true, threadId = 5, isMMS = isMms, attachment = null,
        senderPhoneNumber = "111|222|333", senderName = "", senderPhotoUri = "", subscriptionId = -1,
    )

    private fun stateOf(details: DeliveryDetails, address: String) =
        details.recipients.single { it.person.address == address }.state

    @Test
    fun eachPersonShowsTheirOwnCopy() {
        row("111", status = Sms.STATUS_COMPLETE, dateSent = groupMillis + 5_000)
        row("222", type = Sms.MESSAGE_TYPE_FAILED)
        row("333", status = Sms.STATUS_FAILED)

        val details = loadDeliveryDetails(context, groupMessage(), deliveryReportsOn = true)

        assertThat(details.perPerson).isTrue()
        assertThat(stateOf(details, "111")).isEqualTo(RecipientState.Delivered)
        assertThat(details.recipients.first().timeMillis).isEqualTo(groupMillis + 5_000)
        assertThat(stateOf(details, "222")).isEqualTo(RecipientState.NotSent)
        assertThat(stateOf(details, "333")).isEqualTo(RecipientState.NotDelivered)
    }

    @Test
    fun aReceivedTextWithTheSameWordsAndOldOrOtherTextsAreNotTheCopy() {
        row("111", type = Sms.MESSAGE_TYPE_INBOX, status = Sms.STATUS_COMPLETE)
        row("222", body = "Something else", status = Sms.STATUS_COMPLETE)
        row("333", date = groupMillis - 10 * 60_000L, status = Sms.STATUS_COMPLETE)

        val copies = readGroupCopies(context, groupPeopleOf(groupMessage()), groupMessage())

        assertThat(copies).isEmpty()
        // Nobody has a copy: everybody shows the overall state of the group row.
        val details = loadDeliveryDetails(context, groupMessage(), deliveryReportsOn = true)
        assertThat(details.recipients.map { it.state }.distinct()).containsExactly(RecipientState.Sent)
    }

    @Test
    fun theNewestCopyOfAnAddressWins() {
        row("111", type = Sms.MESSAGE_TYPE_FAILED, date = groupMillis)
        // The retry reused the text a few seconds later and went out.
        row("111", type = Sms.MESSAGE_TYPE_SENT, date = groupMillis + 8_000)

        val copies = readGroupCopies(context, groupPeopleOf(groupMessage()), groupMessage())

        assertThat(copies["111"]?.type).isEqualTo(Sms.MESSAGE_TYPE_SENT)
    }

    @Test
    fun aGroupPictureMessageReadsNoCopiesAndSaysOneStateForAll() {
        row("111", status = Sms.STATUS_COMPLETE)

        val details = loadDeliveryDetails(context, groupMessage(isMms = true), deliveryReportsOn = true)

        assertThat(details.perPerson).isFalse()
        assertThat(details.recipients.map { it.state }.distinct()).containsExactly(RecipientState.Sent)
    }
}
