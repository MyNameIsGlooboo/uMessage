package com.glooboo.umessage.ui.thread

import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadSpamReportTest {
    private fun rule(id: Long, kind: ListKind, type: RuleType, pattern: String, sim: Int? = null) =
        SpamRule(id = id, kind = kind, type = type, pattern = pattern, subscriptionId = sim)

    @Test fun newNumberNeedsABlockRule() {
        val plan = planSpamReport(emptyList(), "+15551230001")
        assertEquals(RuleType.NUMBER, plan.type)
        assertFalse(plan.alreadyBlocked)
        assertTrue(plan.allowRulesToRemove.isEmpty())
    }

    @Test fun senderIdIsReportedAsSenderId() {
        assertEquals(RuleType.SENDER_ID, planSpamReport(emptyList(), "VM-HDFCBK").type)
    }

    @Test fun notSpamAllowRuleIsRemovedInAnyFormat() {
        val allow = rule(1, ListKind.ALLOW, RuleType.NUMBER, "+15551230001")
        val other = rule(2, ListKind.ALLOW, RuleType.NUMBER, "+15559990000")
        val plan = planSpamReport(listOf(allow, other), "(555) 123-0001")
        assertEquals(listOf(allow), plan.allowRulesToRemove)
    }

    @Test fun existingBlockRuleForBothSimsMeansAlreadyBlocked() {
        val block = rule(1, ListKind.BLOCK, RuleType.NUMBER, "+15551230001")
        assertTrue(planSpamReport(listOf(block), "+15551230001").alreadyBlocked)
    }

    @Test fun blockRuleForOneSimStillNeedsARuleForBoth() {
        val block = rule(1, ListKind.BLOCK, RuleType.NUMBER, "+15551230001", sim = 2)
        assertFalse(planSpamReport(listOf(block), "+15551230001").alreadyBlocked)
    }

    @Test fun senderIdsMatchIgnoringCase() {
        val allow = rule(1, ListKind.ALLOW, RuleType.SENDER_ID, "VM-HDFCBK")
        assertEquals(listOf(allow), planSpamReport(listOf(allow), "vm-hdfcbk").allowRulesToRemove)
    }

    @Test fun reportedOnlyWhenABlockRuleCoversTheSenderAndNoAllowRuleOverridesIt() {
        val block = rule(1, ListKind.BLOCK, RuleType.NUMBER, "+15551230001")
        val allow = rule(2, ListKind.ALLOW, RuleType.NUMBER, "+15551230001")
        assertFalse(isAlreadyReported(emptyList(), "+15551230001"))
        assertTrue(isAlreadyReported(listOf(block), "(555) 123-0001"))
        assertFalse(isAlreadyReported(listOf(block, allow), "+15551230001"))
    }

    @Test fun keywordRulesAreNeverTouched() {
        val keyword = rule(1, ListKind.ALLOW, RuleType.KEYWORD, "+15551230001")
        assertTrue(planSpamReport(listOf(keyword), "+15551230001").allowRulesToRemove.isEmpty())
    }
}
