package com.glooboo.umessage.spam.engine

import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.UnknownSenderMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the filter is unsure (a lookup failed, a rule misbehaves) it delivers, and it never throws. */
class DefaultSpamFilterFailSafeTest {

    private val normalizer = NumberNormalizer { "IN" }
    private val strict = SpamSettings(mode = UnknownSenderMode.STRICT, otpExemption = false)

    private class LookupContacts(private val answer: (String) -> ContactLookup) : ContactChecker {
        override fun isContact(address: String) = answer(address) == ContactLookup.FOUND
        override fun lookup(address: String) = answer(address)
    }

    private class BrokenLegacy(private val unknown: Boolean = false) : LegacyBlocklist {
        override fun isNumberBlocked(address: String): Boolean = error("bad entry")
        override fun blockedKeywords(): Collection<String> = error("prefs gone")
        override fun blockUnknownNumbers() = unknown
    }

    private fun filter(
        rules: List<SpamRule> = emptyList(),
        settings: SpamSettings = strict,
        contacts: ContactChecker = LookupContacts { ContactLookup.NOT_FOUND },
        legacy: LegacyBlocklist = NoLegacyBlocklist,
    ) = DefaultSpamFilter({ rules }, { settings }, contacts, normalizer, legacy)

    private fun msg(address: String = "+919876543210", body: String = "Dinner at 8?") = IncomingMessage(address, body, 1)

    private fun rule(kind: ListKind, type: RuleType, pattern: String, id: Long = 1) =
        SpamRule(id = id, kind = kind, type = type, pattern = pattern)

    // T4 / SE-18: a contacts lookup that fails must not turn a contact into an unknown sender.

    @Test fun contactLookupFailureDeliversInStrictMode() {
        val decision = filter(contacts = LookupContacts { ContactLookup.UNKNOWN }).evaluate(msg())
        assertEquals(FilterDecision.Allow(DecisionReason.CONTACTS_UNAVAILABLE), decision)
    }

    @Test fun contactLookupThatThrowsDeliversInStrictMode() {
        val decision = filter(contacts = LookupContacts { error("provider busy") }).evaluate(msg())
        assertEquals(FilterDecision.Allow(DecisionReason.CONTACTS_UNAVAILABLE), decision)
    }

    @Test fun contactLookupFailureDeliversWithLegacyBlockUnknown() {
        val legacy = object : LegacyBlocklist by NoLegacyBlocklist {
            override fun blockUnknownNumbers() = true
        }
        val decision = filter(contacts = LookupContacts { ContactLookup.UNKNOWN }, legacy = legacy).evaluate(msg())
        assertEquals(FilterDecision.Allow(DecisionReason.CONTACTS_UNAVAILABLE), decision)
    }

    @Test fun contactLookupFailureStillAppliesBlockRules() {
        val f = filter(
            rules = listOf(rule(ListKind.BLOCK, RuleType.KEYWORD, "dinner", id = 9)),
            contacts = LookupContacts { ContactLookup.UNKNOWN },
        )
        assertEquals(FilterDecision.Block(DecisionReason.BLOCK_RULE, 9), f.evaluate(msg()))
    }

    @Test fun confirmedStrangerIsStillBlockedInStrictMode() {
        assertEquals(FilterDecision.Block(DecisionReason.UNKNOWN_SENDER), filter().evaluate(msg()))
    }

    // SE-31: an MMS whose sender could not be read arrives with a blank address.

    @Test fun blankSenderIsNotBlockedAsUnknown() {
        val decision = filter().evaluate(msg(address = ""))
        assertEquals(FilterDecision.Allow(DecisionReason.CONTACTS_UNAVAILABLE), decision)
    }

    @Test fun blankSenderStillMatchesBodyRules() {
        val f = filter(rules = listOf(rule(ListKind.BLOCK, RuleType.KEYWORD, "dinner", id = 3)))
        assertEquals(FilterDecision.Block(DecisionReason.BLOCK_RULE, 3), f.evaluate(msg(address = "  ")))
    }

    // SE-17: a broken old Fossify block list skips only itself.

    @Test fun brokenLegacyListDoesNotStopTheFilter() {
        val f = filter(
            rules = listOf(rule(ListKind.BLOCK, RuleType.KEYWORD, "prize", id = 4)),
            legacy = BrokenLegacy(),
        )
        assertEquals(FilterDecision.Block(DecisionReason.BLOCK_RULE, 4), f.evaluate(msg(body = "You won a prize")))
        assertEquals(FilterDecision.Block(DecisionReason.UNKNOWN_SENDER), f.evaluate(msg(body = "hello")))
    }

    @Test fun brokenLegacyListStillAllowsContacts() {
        val f = filter(legacy = BrokenLegacy(), contacts = LookupContacts { ContactLookup.FOUND })
        assertEquals(FilterDecision.Allow(DecisionReason.CONTACT), f.evaluate(msg()))
    }

    // SE-13: a REGEX rule that takes too long is turned off and the message is delivered.

    @Test fun slowRegexRuleIsTurnedOffAndMessageDelivered() {
        val f = filter(
            rules = listOf(rule(ListKind.BLOCK, RuleType.REGEX, ".*.*.*.*x", id = 5)),
            settings = SpamSettings(mode = UnknownSenderMode.OFF),
        )
        val body = "a".repeat(2_000)
        val first = System.nanoTime()
        assertEquals(FilterDecision.Allow(DecisionReason.MODE_OFF), f.evaluate(msg(body = body)))
        val firstMs = (System.nanoTime() - first) / 1_000_000
        assertTrue("first check took $firstMs ms", firstMs < 2_000)

        // Turned off: even a body it would match is no longer blocked, and the check costs nothing.
        val second = System.nanoTime()
        assertEquals(FilterDecision.Allow(DecisionReason.MODE_OFF), f.evaluate(msg(body = "ax")))
        val secondMs = (System.nanoTime() - second) / 1_000_000
        assertTrue("second check took $secondMs ms", secondMs < 50)
    }

    @Test fun fastRegexRuleStillBlocks() {
        val f = filter(
            rules = listOf(rule(ListKind.BLOCK, RuleType.REGEX, "win\\s+\\$\\d+", id = 6)),
            settings = SpamSettings(mode = UnknownSenderMode.OFF),
        )
        assertEquals(FilterDecision.Block(DecisionReason.BLOCK_RULE, 6), f.evaluate(msg(body = "You WIN \$500 now")))
    }
}
