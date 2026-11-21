package com.glooboo.umessage.spam.engine

import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.UnknownSenderMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Step N review (N5-7): with the provider-truth flag off, Smart mode lets a code from an unknown sender through. The
 * wider detector must not let advertisements and prize scams through with it: a prize word always holds the text, an
 * advertising word only passes as a footer after the code's sentence. Every text and number is made up.
 */
class DefaultSpamFilterCodeExemptionTest {

    private val normalizer = NumberNormalizer { "IN" }

    private object NoContacts : ContactChecker {
        override fun isContact(address: String) = false
        override fun canReadContacts() = true
        override fun sentTo(address: String) = ContactLookup.NOT_FOUND
    }

    private fun smart(body: String): FilterDecision {
        val previous = ProviderTruthFlag.enabled
        ProviderTruthFlag.enabled = false
        try {
            val settings = SpamSettings(mode = UnknownSenderMode.SMART)
            val filter = DefaultSpamFilter({ emptyList() }, { settings }, NoContacts, normalizer)
            return filter.evaluate(IncomingMessage("VM-EXMPL", body, 1))
        } finally {
            ProviderTruthFlag.enabled = previous
        }
    }

    private fun assertLetThrough(body: String) =
        assertEquals(body, FilterDecision.Allow(DecisionReason.OTP_EXEMPTION), smart(body))

    private fun assertHeld(body: String) =
        assertEquals(body, FilterDecision.Block(DecisionReason.UNKNOWN_SENDER), smart(body))

    @Test fun aCodeWithAnAdvertisingFooterIsLetThrough() {
        assertLetThrough("کد تایید: 1234.\nتخفیف ۲۰٪ در خرید بعدی")
        assertLetThrough("Your verification code is 482913. Get 20% off your next ride!")
        assertLetThrough("Your OTP is 482915")
    }

    @Test fun anAdvertisementOrAPrizeScamIsHeld() {
        listOf(
            "Sign in with SAVE20 and get 5000 points",
            "Congratulations winner! Your OTP is 482913. Send it to 30001234 to claim your prize",
            "شما برنده قرعه کشی شدید. کد تایید: 482913. برای دریافت جایزه به example.com مراجعه کنید",
            "تخفیف ۵۰٪ نمونه! کد تایید شما برای دریافت: 482913",
            "Your OTP is 482913, enjoy 20% off",
            "Winner! password: 12345678",
        ).forEach { assertHeld(it) }
    }
}
