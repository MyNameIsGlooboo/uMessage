package com.glooboo.umessage.ui.spam

import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.engine.NumberNormalizer
import com.glooboo.umessage.spam.engine.RuleMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleDryRunTest {
    private val matcher = RuleMatcher(NumberNormalizer { "GB" })

    private fun msg(address: String, body: String, sub: Int = 1) = DryRunMessage(address, body, sub)
    private fun keyword(word: String, sub: Int? = null, kind: ListKind = ListKind.BLOCK) =
        SpamRule(kind = kind, type = RuleType.KEYWORD, pattern = word, subscriptionId = sub)

    @Test
    fun countsMatchesAndListsDistinctSenders() {
        val messages = listOf(
            msg("AD-SHOP", "Big sale today"), msg("AD-SHOP", "sale again"), msg("+447700900001", "SALE!"),
            msg("Mum", "dinner?"),
        )
        val part = RuleDryRun.scan(matcher, keyword("sale"), messages)
        assertEquals(4, part.scanned)
        assertEquals(3, part.matched)
        assertEquals(listOf("AD-SHOP", "+447700900001"), part.senders)
    }

    @Test
    fun capsTheScanAndTheExamples() {
        val messages = (1..10).map { msg("S$it", "free prize") }
        val part = RuleDryRun.scan(matcher, keyword("free"), messages, cap = 5)
        assertEquals(5, part.scanned)
        assertEquals(5, part.matched)
        assertEquals(RuleDryRun.MAX_EXAMPLES, part.senders.size)
    }

    @Test
    fun hidesSendersWhenAskedButKeepsTheCount() {
        val part = RuleDryRun.scan(matcher, keyword("sale"), listOf(msg("AD-SHOP", "sale")), showSenders = false)
        assertEquals(1, part.matched)
        assertTrue(part.senders.isEmpty())
    }

    @Test
    fun ruleForOneSimOnlySeesThatSim() {
        val messages = listOf(msg("A", "sale", sub = 1), msg("B", "sale", sub = 2))
        val part = RuleDryRun.scan(matcher, keyword("sale", sub = 2), messages)
        assertEquals(2, part.scanned)
        assertEquals(1, part.matched)
        assertEquals(listOf("B"), part.senders)
    }

    @Test
    fun emptyInputGivesZeroes() {
        assertEquals(DryRunPart(), RuleDryRun.scan(matcher, keyword("sale"), emptyList()))
        assertTrue(RuleDryRunResult().isEmpty)
    }

    @Test
    fun regexRuleMatchesAndBadPatternMatchesNothing() {
        val rule = SpamRule(kind = ListKind.BLOCK, type = RuleType.REGEX, pattern = "win \\d+")
        assertEquals(1, RuleDryRun.scan(matcher, rule, listOf(msg("X", "you win 100 now"), msg("Y", "hello"))).matched)
        val bad = rule.copy(pattern = "(")
        assertEquals(0, RuleDryRun.scan(matcher, bad, listOf(msg("X", "(((" ))).matched)
    }

    @Test
    fun stopsWhenToldTo() {
        var calls = 0
        val part = RuleDryRun.scan(matcher, keyword("a"), List(10) { msg("S", "a") }, keepGoing = { ++calls <= 3 })
        assertEquals(3, part.scanned)
    }

    @Test
    fun blockedMessagesAreNewestFirstCappedAndTextOnly() {
        fun blocked(id: Long, at: Long, body: String) = BlockedMessage(
            id = id, address = "S$id", body = body, subscriptionId = 1, isMms = false, receivedAt = at,
            reason = DecisionReason.BLOCK_RULE,
        )
        val list = RuleDryRun.blockedMessages(
            listOf(blocked(1, 10, "old"), blocked(2, 30, "new"), blocked(3, 20, " "), blocked(4, 25, "mid")),
            cap = 2,
        )
        assertEquals(listOf("S2", "S4"), list.map { it.address })
    }
}
