package com.glooboo.umessage.helpers

import android.provider.Telephony
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.models.Message
import com.google.common.truth.Truth.assertThat
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.junit.Test
import org.junit.runner.RunWith

/** The stable key that ties a note to a message across restores (see [MessageNoteKey]). */
@RunWith(AndroidJUnit4::class)
class MessageNoteKeyTest {

    private fun message(
        id: Long = 1,
        body: String = "Paid 12.50 at the shop",
        from: String = "+15550001234",
        date: Int = 1_700_000_000,
        type: Int = Telephony.Sms.MESSAGE_TYPE_INBOX,
        mms: Boolean = false,
    ) = Message(
        id = id, body = body, type = type, status = 0, participants = ArrayList(), date = date, read = true,
        threadId = 7, isMMS = mms, attachment = null, senderPhoneNumber = from, senderName = "",
        senderPhotoUri = "", subscriptionId = 0,
    )

    @Test
    fun theProviderIdAndThreadDoNotChangeTheKey() {
        val moved = message(id = 999).copy(threadId = 3)
        assertThat(MessageNoteKey.of(message(id = 1))).isEqualTo(MessageNoteKey.of(moved))
    }

    @Test
    fun plusAndZeroPrefixesOfTheSameNumberAgree() {
        val international = MessageNoteKey.of(message(from = "+1 555 000 1234"))
        val national = MessageNoteKey.of(message(from = "0555 000 1234"))
        val bare = MessageNoteKey.of(message(from = "5550001234"))
        assertThat(national).isEqualTo(international)
        assertThat(bare).isEqualTo(international)
    }

    @Test
    fun persianAndArabicIndicDigitsInTheAddressAgreeWithAscii() {
        val ascii = MessageNoteKey.of(message(from = "+4471123456"))
        val persian = MessageNoteKey.of(message(from = "+۴۴۷۱۱۲۳۴۵۶"))
        val arabic = MessageNoteKey.of(message(from = "+٤٤٧١١٢٣٤٥٦"))
        assertThat(persian).isEqualTo(ascii)
        assertThat(arabic).isEqualTo(ascii)
    }

    @Test
    fun aSenderNameInsteadOfANumberKeepsItsLettersCaseFolded() {
        assertThat(MessageNoteKey.of(message(from = "MyBank"))).isEqualTo(MessageNoteKey.of(message(from = "mybank")))
        val other = MessageNoteKey.of(message(from = "OtherBk"))
        assertThat(MessageNoteKey.of(message(from = "MyBank"))).isNotEqualTo(other)
    }

    @Test
    fun differentBodyDateOrAddressGiveDifferentKeys() {
        val base = MessageNoteKey.of(message())
        assertThat(MessageNoteKey.of(message(body = "Paid 12.60 at the shop"))).isNotEqualTo(base)
        assertThat(MessageNoteKey.of(message(date = 1_700_000_001))).isNotEqualTo(base)
        assertThat(MessageNoteKey.of(message(from = "+15550009999"))).isNotEqualTo(base)
    }

    @Test
    fun whiteSpaceInTheBodyDoesNotMatter() {
        val spaced = MessageNoteKey.of(message(body = "  Paid   12.50 at\nthe shop "))
        assertThat(spaced).isEqualTo(MessageNoteKey.of(message()))
    }

    @Test
    fun theSameMessageTwiceSharesOneKey() {
        assertThat(MessageNoteKey.of(message(id = 5))).isEqualTo(MessageNoteKey.of(message(id = 6)))
    }

    @Test
    fun smsAndMmsOfOtherwiseTheSameContentAreDifferentKeys() {
        val sms = MessageNoteKey.of(message(mms = false))
        val mms = MessageNoteKey.of(message(mms = true))
        assertThat(sms).isNotEqualTo(mms)
        assertThat(MessageNoteKey.isMms(sms)).isFalse()
        assertThat(MessageNoteKey.isMms(mms)).isTrue()
    }

    @Test
    fun aSentMessageKeepsItsKeyWhileItMovesFromOutboxToSent() {
        val number = PhoneNumber("+15550001234", 0, "", "+15550001234")
        val participants = arrayListOf(SimpleContact(1, 1, "", "", arrayListOf(number), ArrayList(), ArrayList()))
        val outbox = message(type = Telephony.Sms.MESSAGE_TYPE_OUTBOX, from = "").copy(participants = participants)
        val sent = message(type = Telephony.Sms.MESSAGE_TYPE_SENT, from = "").copy(participants = participants)
        assertThat(MessageNoteKey.of(outbox)).isEqualTo(MessageNoteKey.of(sent))
        assertThat(MessageNoteKey.of(sent)).isNotEqualTo(MessageNoteKey.of(message()))
    }

    @Test
    fun theKeyCarriesItsDateAndKindForRelinking() {
        val key = MessageNoteKey.of(message(date = 1_700_000_042))
        assertThat(MessageNoteKey.dateSeconds(key)).isEqualTo(1_700_000_042)
        assertThat(MessageNoteKey.dateSeconds("garbage")).isNull()
        assertThat(MessageNoteKey.isMms("garbage")).isFalse()
    }
}
