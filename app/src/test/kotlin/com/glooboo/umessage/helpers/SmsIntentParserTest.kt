package com.glooboo.umessage.helpers

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/**
 * sms:/smsto: links come from any app or web page, so malformed ones must never crash. Each part is decoded exactly
 * once: the recipients by [Uri], the body by the parser.
 */
@RunWith(AndroidJUnit4::class)
class SmsIntentParserTest {

    private fun parse(link: String, action: String = Intent.ACTION_SENDTO) =
        SmsIntentParser.parse(Intent(action, Uri.parse(link)))

    @Test
    fun plainLinkGivesRecipientAndBody() {
        assertThat(parse("sms:+15550100?body=hello%20there")).isEqualTo("hello there" to "+15550100")
    }

    @Test
    fun encodedPercentInBodyIsDecodedOnce() {
        // Decoding twice turned "100%25" into "100%" and then threw on the lone '%'.
        assertThat(parse("sms:123?body=100%25")!!.first).isEqualTo("100%")
    }

    @Test
    fun malformedEscapeInBodyKeepsTheRawText() {
        assertThat(parse("sms:123?body=50%zz")!!.first).isEqualTo("50%zz")
    }

    @Test
    fun malformedEscapeInRecipientDoesNotThrow() {
        val result = parse("sms:%zz?body=hi")
        assertThat(result).isNotNull()
        assertThat(result!!.first).isEqualTo("hi")
        // Uri turns the bad escape into a NUL (or replacement) character; no control character may survive.
        assertThat(result.second.none { Character.isISOControl(it) }).isTrue()
    }

    @Test
    fun brokenEscapeLeavesNoRecipientSoNoThreadOpens() {
        // On the emulator sms:%zz decoded to NUL + "z"; a lone "z" is not an address.
        assertThat(parse("sms:%zz")!!.second).isEmpty()
        assertThat(parse("sms:%00z")!!.second).isEmpty()
    }

    @Test
    fun addressableRecipients() {
        assertThat(SmsIntentParser.isAddressable("+1 (555) 010-0100")).isTrue()
        assertThat(SmsIntentParser.isAddressable("12345")).isTrue()
        assertThat(SmsIntentParser.isAddressable("VM-HDFCBK")).isTrue()
        assertThat(SmsIntentParser.isAddressable("someone@example.test")).isTrue()
        assertThat(SmsIntentParser.isAddressable("z")).isFalse()
        assertThat(SmsIntentParser.isAddressable("�z")).isFalse()
        assertThat(SmsIntentParser.isAddressable("()-")).isFalse()
        assertThat(SmsIntentParser.isAddressable("")).isFalse()
    }

    @Test
    fun nulRecipientIsDropped() {
        assertThat(parse("sms:%00?body=hi")).isEqualTo("hi" to "")
    }

    @Test
    fun newlineRecipientIsDropped() {
        assertThat(parse("sms:%0A")!!.second).isEmpty()
    }

    @Test
    fun controlCharactersAreStrippedFromAValidNumber() {
        assertThat(parse("sms:%00555%0A0100,%00")!!.second).isEqualTo("5550100")
    }

    @Test
    fun formatCharactersAndUnpairedSurrogatesAreStripped() {
        assertThat(SmsIntentParser.sanitizeRecipient("‎555\uD800 0100\uDC00")).isEqualTo("555 0100")
        // A valid surrogate pair (emoji) is kept.
        assertThat(SmsIntentParser.sanitizeRecipient("Bob 😀")).isEqualTo("Bob 😀")
    }

    @Test
    fun encodedPercentInRecipientIsDecodedOnceAndNotAgain() {
        // The new-conversation screen used to URL-decode this "%zz" a second time and crash. Decoded once it is
        // "%zz", which is not an address, so no recipient is left.
        assertThat(parse("smsto:%25zz")!!.second).isEmpty()
        assertThat(parse("smsto:%25555")!!.second).isEmpty()
        assertThat(parse("smsto:555%2E0100")!!.second).isEqualTo("555.0100")
    }

    @Test
    fun encodedPlusInRecipientSurvives() {
        assertThat(parse("smsto:%2B15550100")!!.second).isEqualTo("+15550100")
    }

    @Test
    fun severalRecipientsAreJoinedWithSemicolons() {
        assertThat(parse("sms:111,222;333", Intent.ACTION_VIEW)!!.second).isEqualTo("111;222;333")
    }

    @Test
    fun otherActionsAreIgnored() {
        assertThat(parse("sms:123", Intent.ACTION_SEND)).isNull()
    }
}
