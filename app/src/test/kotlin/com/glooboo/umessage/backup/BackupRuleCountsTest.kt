package com.glooboo.umessage.backup

import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BackupRuleCountsTest {
    private fun keyword(pattern: String, kind: ListKind = ListKind.BLOCK) =
        SpamRule(kind = kind, type = RuleType.KEYWORD, pattern = pattern, createdAt = 1)

    @Test fun rulesAlreadyOnThePhoneAreCountedAsPresent() {
        val (fresh, present) = SettingsBackupFormat.newRules(
            incoming = listOf(keyword("sale"), keyword("win"), keyword("sale ")),
            existing = listOf(keyword("sale")),
        )
        assertThat(fresh.map { it.pattern }).containsExactly("win")
        assertThat(present).isEqualTo(2)
    }

    @Test fun sameTextWithAnotherKindIsNew() {
        val (fresh, present) = SettingsBackupFormat.newRules(
            incoming = listOf(keyword("sale", ListKind.ALLOW)),
            existing = listOf(keyword("sale")),
        )
        assertThat(fresh).hasSize(1)
        assertThat(present).isEqualTo(0)
    }

    @Test fun sameRuleForAnotherSimIsPresent() {
        // J17: the database keeps one rule per number whatever the SIM, so the SIM does not tell rules apart.
        val (fresh, present) = SettingsBackupFormat.newRules(
            incoming = listOf(keyword("sale").copy(subscriptionId = 2), keyword("sale")),
            existing = listOf(keyword("sale").copy(subscriptionId = 1)),
        )
        assertThat(fresh).isEmpty()
        assertThat(present).isEqualTo(2)
    }

    @Test fun sameRuleForTheSameSimIsPresent() {
        val (fresh, present) = SettingsBackupFormat.newRules(
            incoming = listOf(keyword("sale").copy(subscriptionId = 1)),
            existing = listOf(keyword("sale").copy(subscriptionId = 1)),
        )
        assertThat(fresh).isEmpty()
        assertThat(present).isEqualTo(1)
    }

    @Test fun rulesOverTheLimitAreCounted() {
        val many = List(SettingsBackupFormat.MAX_RULES + 3) { keyword("k$it") }
        val text = SettingsBackupFormat.encode(BackupContent(many, emptyList(), BackupSettings()), 1)
        val back = SettingsBackupFormat.decode(text)
        assertThat(back.rules).hasSize(SettingsBackupFormat.MAX_RULES)
        assertThat(back.droppedRules).isEqualTo(3)
        assertThat(back.droppedForwardRules).isEqualTo(0)
    }
}
