package com.glooboo.umessage.spam.engine

import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.AllowGroupMember
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.UnknownSenderMode
import com.glooboo.umessage.testing.ProviderTruthFlagRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * [DefaultSpamFilter.evaluate] on both sides of [ProviderTruthFlag]: off, it decides exactly as 0.4.14 (per message,
 * SIM-scoped rules, the hidden "wrote to" check, Smart and Strict); on, it is the probe of the sender order of
 * section 2.1 and writes nothing. Every number and text is made up.
 */
class DefaultSpamFilterProviderTruthTest {

    @get:Rule val flag = ProviderTruthFlagRule(enabled = false)

    private val normalizer = NumberNormalizer { "US" }

    private class Contacts(private val sent: Set<String> = emptySet()) : ContactChecker {
        var sentAsked = 0
        override fun isContact(address: String) = false
        override fun sentTo(address: String): ContactLookup {
            sentAsked++
            return if (address in sent) ContactLookup.FOUND else ContactLookup.NOT_FOUND
        }
    }

    private fun filter(
        rules: List<SpamRule> = emptyList(),
        settings: SpamSettings = SpamSettings(),
        contacts: ContactChecker = Contacts(),
        members: Map<String, Set<AllowGroup>> = emptyMap(),
    ) = DefaultSpamFilter(
        rules = { rules },
        settings = { settings },
        contacts = contacts,
        normalizer = normalizer,
        groups = GroupMembership {
            members.flatMap { (address, groups) -> groups.map { AllowGroupMember(it, address, address) } }
        },
    )

    private fun msg(body: String, address: String = SENDER, sub: Int = 1) = IncomingMessage(address, body, sub)

    /** A blocked word for SIM 2 only, as 0.4.14 could store it. */
    private fun wordOnSim2() =
        SpamRule(id = 7, kind = ListKind.BLOCK, type = RuleType.KEYWORD, pattern = "lottery", subscriptionId = 2)

    @Test fun flagOff_aCodeIsLetInPerMessageAndTheNextAdvertIsHeld() {
        val f = filter()
        assertEquals(FilterDecision.Allow(DecisionReason.OTP_EXEMPTION), f.evaluate(msg(CODE)))
        assertEquals(FilterDecision.Block(DecisionReason.UNKNOWN_SENDER), f.evaluate(msg("Weekend sale")))
    }

    @Test fun flagOff_strictHoldsCodesAndOffLetsAllIn() {
        val strict = filter(settings = SpamSettings(mode = UnknownSenderMode.STRICT))
        assertEquals(FilterDecision.Block(DecisionReason.UNKNOWN_SENDER), strict.evaluate(msg(CODE)))
        val off = filter(settings = SpamSettings(mode = UnknownSenderMode.OFF, filterOn = true))
        assertEquals(FilterDecision.Allow(DecisionReason.MODE_OFF), off.evaluate(msg("Weekend sale")))
    }

    @Test fun flagOff_theHiddenWroteToCheckAndSimScopedRulesStay() {
        val known = filter(contacts = Contacts(sent = setOf(SENDER)))
        assertEquals(FilterDecision.Allow(DecisionReason.KNOWN_CONVERSATION), known.evaluate(msg("hi")))

        val scoped = filter(rules = listOf(wordOnSim2()), settings = SpamSettings(mode = UnknownSenderMode.OFF))
        assertEquals(FilterDecision.Allow(DecisionReason.MODE_OFF), scoped.evaluate(msg("lottery", sub = 1)))
        assertEquals(FilterDecision.Block(DecisionReason.BLOCK_RULE, 7), scoped.evaluate(msg("lottery", sub = 2)))
    }

    @Test fun flagOff_userAllowBeatsBlockAsIn0414EvenForAnAutomaticAllow() {
        val rules = listOf(
            SpamRule(id = 1, kind = ListKind.ALLOW, type = RuleType.NUMBER, pattern = SENDER, note = AUTO_ALLOW),
            SpamRule(id = 2, kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = SENDER),
        )
        assertEquals(FilterDecision.Allow(DecisionReason.ALLOW_RULE, 1), filter(rules).evaluate(msg("hi")))
    }

    @Test fun flagOn_theSenderOrderDecidesAndTheWroteToCheckIsGone() {
        ProviderTruthFlag.enabled = true
        val contacts = Contacts(sent = setOf(SENDER))
        val f = filter(contacts = contacts)
        assertEquals(FilterDecision.Block(DecisionReason.UNKNOWN_SENDER), f.evaluate(msg("hi")))
        assertEquals(0, contacts.sentAsked)
        assertEquals(FilterDecision.Allow(DecisionReason.OTP_EXEMPTION), f.evaluate(msg(CODE)))
    }

    @Test fun flagOn_anAutomaticAllowLosesToABlockAndAMemberIsShown() {
        ProviderTruthFlag.enabled = true
        val rules = listOf(
            SpamRule(id = 1, kind = ListKind.ALLOW, type = RuleType.NUMBER, pattern = SENDER, note = AUTO_ALLOW),
            SpamRule(id = 2, kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = SENDER),
        )
        assertEquals(FilterDecision.Block(DecisionReason.BLOCK_RULE, 2), filter(rules).evaluate(msg("hi")))

        val member = filter(members = mapOf(OTHER to setOf(AllowGroup.WROTE)))
        assertEquals(FilterDecision.Allow(DecisionReason.GROUP_MEMBER), member.evaluate(msg("hi", address = OTHER)))
    }

    @Test fun flagOn_rulesCoverBothSimsAndTheSwitchReplacesTheMode() {
        ProviderTruthFlag.enabled = true
        val settings = SpamSettings(mode = UnknownSenderMode.STRICT, filterOn = false)
        val f = filter(rules = listOf(wordOnSim2()), settings = settings)
        assertEquals(FilterDecision.Block(DecisionReason.BLOCK_RULE, 7), f.evaluate(msg("lottery", sub = 1)))
        assertEquals(FilterDecision.Allow(DecisionReason.MODE_OFF), f.evaluate(msg("hello", sub = 1)))
    }

    private companion object {
        const val SENDER = "+15550001234"
        const val OTHER = "+15550005678"
        const val CODE = "Your verification code is 482915"
        const val AUTO_ALLOW = "@spamless:allowed_from_keyword"
    }
}
