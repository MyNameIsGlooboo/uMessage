package com.glooboo.umessage.ui.spam

import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** ID-42: a block rule that an allow rule covers is marked as overridden. */
class RuleConflictsTest {

    private fun rule(id: Long, kind: ListKind, type: RuleType, pattern: String, sim: Int? = null) =
        SpamRule(id, kind, type, pattern, sim)

    @Test
    fun blockedSenderCoveredByAllowIsOverridden() {
        val allow = rule(1, ListKind.ALLOW, RuleType.SENDER_ID, "HOMEPOST")
        val block = rule(2, ListKind.BLOCK, RuleType.SENDER_ID, "homepost")
        assertThat(RuleConflicts.overriddenBy(listOf(allow, block), block)).isEqualTo(allow)
        assertThat(RuleConflicts.overriddenBlockIds(listOf(allow, block))).containsExactly(2L)
    }

    @Test
    fun allowedPrefixCoversABlockedNumber() {
        val allow = rule(1, ListKind.ALLOW, RuleType.PREFIX, "+4420")
        val block = rule(2, ListKind.BLOCK, RuleType.NUMBER, "+442012345")
        assertThat(RuleConflicts.overriddenBy(listOf(allow, block), block)).isEqualTo(allow)
    }

    @Test
    fun simScopedAllowDoesNotCoverBothSims() {
        val allow = rule(1, ListKind.ALLOW, RuleType.SENDER_ID, "BANK", sim = 1)
        val both = rule(2, ListKind.BLOCK, RuleType.SENDER_ID, "BANK")
        val sameSim = rule(3, ListKind.BLOCK, RuleType.SENDER_ID, "BANK", sim = 1)
        assertThat(RuleConflicts.overriddenBy(listOf(allow, both, sameSim), both)).isNull()
        assertThat(RuleConflicts.overriddenBy(listOf(allow, both, sameSim), sameSim)).isEqualTo(allow)
    }

    @Test
    fun keywordRulesAndAllowRulesAreNeverMarked() {
        val allow = rule(1, ListKind.ALLOW, RuleType.SENDER_ID, "BANK")
        val keyword = rule(2, ListKind.BLOCK, RuleType.KEYWORD, "bank")
        assertThat(RuleConflicts.overriddenBy(listOf(allow, keyword), keyword)).isNull()
        assertThat(RuleConflicts.overriddenBy(listOf(allow, keyword), allow)).isNull()
    }
}
