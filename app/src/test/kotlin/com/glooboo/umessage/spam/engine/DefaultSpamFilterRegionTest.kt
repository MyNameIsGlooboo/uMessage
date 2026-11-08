package com.glooboo.umessage.spam.engine

import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.UnknownSenderMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** I24: the home-country setting inside the filter order. */
class DefaultSpamFilterRegionTest {

    private val normalizer = NumberNormalizer { "GB" }
    private val foreignNumber = "+919876543210"

    private class Contacts(
        private val contacts: Set<String> = emptySet(),
        private val sent: Set<String> = emptySet(),
        private val readable: Boolean = true,
    ) : ContactChecker {
        override fun isContact(address: String) = readable && address in contacts
        override fun canReadContacts() = readable
        override fun sentTo(address: String) =
            if (address in sent) ContactLookup.FOUND else ContactLookup.NOT_FOUND
    }

    private fun filter(
        mode: UnknownSenderMode = UnknownSenderMode.OFF,
        region: String = "GB",
        rules: List<SpamRule> = emptyList(),
        contacts: ContactChecker = Contacts(),
    ) = DefaultSpamFilter(
        { rules }, { SpamSettings(mode = mode, blockOutsideRegion = region) }, contacts, normalizer,
    )

    private fun decide(f: DefaultSpamFilter, address: String, body: String = "hello") =
        f.evaluate(IncomingMessage(address, body, 1))

    @Test
    fun offByDefaultNothingIsHeldForItsCountry() {
        val decision = decide(filter(region = ""), foreignNumber)
        assertThat(decision).isEqualTo(FilterDecision.Allow(DecisionReason.MODE_OFF))
    }

    @Test
    fun foreignNumberIsHeldWithItsOwnReasonInSmartAndStrict() {
        listOf(UnknownSenderMode.SMART, UnknownSenderMode.STRICT).forEach {
            assertThat(decide(filter(mode = it), foreignNumber))
                .isEqualTo(FilterDecision.Block(DecisionReason.FOREIGN_NUMBER))
        }
    }

    @Test
    fun foreignNumberIsNotHeldWhenTheModeIsOff() {
        assertThat(decide(filter(mode = UnknownSenderMode.OFF), foreignNumber))
            .isEqualTo(FilterDecision.Allow(DecisionReason.MODE_OFF))
        val code = "Your verification code is 482916. Do not share it."
        assertThat(decide(filter(mode = UnknownSenderMode.OFF), foreignNumber, code))
            .isEqualTo(FilterDecision.Allow(DecisionReason.MODE_OFF))
    }

    @Test
    fun homeNumbersShortCodesAndSenderNamesAreNotHeld() {
        val f = filter()
        listOf("+447121234567", "07121234567", "30001234", "AX-HDFCBK").forEach {
            assertThat(decide(f, it)).isEqualTo(FilterDecision.Allow(DecisionReason.MODE_OFF))
        }
    }

    @Test
    fun aContactIsNeverHeld() {
        val f = filter(contacts = Contacts(contacts = setOf(foreignNumber)))
        assertThat(decide(f, foreignNumber)).isEqualTo(FilterDecision.Allow(DecisionReason.CONTACT))
    }

    @Test
    fun someoneTheUserWroteToIsNeverHeld() {
        val f = filter(mode = UnknownSenderMode.STRICT, contacts = Contacts(sent = setOf(foreignNumber)))
        assertThat(decide(f, foreignNumber)).isEqualTo(FilterDecision.Allow(DecisionReason.KNOWN_CONVERSATION))
    }

    @Test
    fun anAllowRuleWinsAndABlockRuleKeepsItsOwnReason() {
        val allow = SpamRule(1, ListKind.ALLOW, RuleType.NUMBER, foreignNumber)
        assertThat(decide(filter(rules = listOf(allow)), foreignNumber))
            .isEqualTo(FilterDecision.Allow(DecisionReason.ALLOW_RULE, 1L))
        val block = SpamRule(2, ListKind.BLOCK, RuleType.PREFIX, "+91")
        assertThat(decide(filter(rules = listOf(block)), foreignNumber))
            .isEqualTo(FilterDecision.Block(DecisionReason.BLOCK_RULE, 2L))
    }

    @Test
    fun withoutContactsAccessNothingIsHeld() {
        val f = filter(mode = UnknownSenderMode.STRICT, contacts = Contacts(readable = false))
        assertThat(decide(f, foreignNumber)).isEqualTo(FilterDecision.Allow(DecisionReason.CONTACTS_UNAVAILABLE))
    }

    @Test
    fun smartModeStillLetsACodeWithoutALinkThrough() {
        val smart = filter(mode = UnknownSenderMode.SMART)
        val code = "Your verification code is 482916. Do not share it."
        assertThat(decide(smart, foreignNumber, code)).isEqualTo(FilterDecision.Allow(DecisionReason.OTP_EXEMPTION))
        val phishing = "Your verification code is 482916. Confirm at http://example.test/x"
        assertThat(decide(smart, foreignNumber, phishing))
            .isEqualTo(FilterDecision.Block(DecisionReason.FOREIGN_NUMBER))
    }

    @Test
    fun strictHoldsForeignCodesToo() {
        val code = "Your verification code is 482916. Do not share it."
        assertThat(decide(filter(mode = UnknownSenderMode.STRICT), foreignNumber, code))
            .isEqualTo(FilterDecision.Block(DecisionReason.FOREIGN_NUMBER))
    }
}
