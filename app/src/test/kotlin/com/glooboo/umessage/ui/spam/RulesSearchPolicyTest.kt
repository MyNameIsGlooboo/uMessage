package com.glooboo.umessage.ui.spam

import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RulesSearchPolicyTest {

    private fun rule(id: Long, type: RuleType) = SpamRule(id, ListKind.BLOCK, type, "value$id")

    @Test
    fun searchAppearsOnlyForLongListsOrWhileFiltering() {
        assertThat(RulesSearchPolicy.showSearch(1, filtering = false)).isFalse()
        assertThat(RulesSearchPolicy.showSearch(RulesSearchPolicy.MIN_ROWS_FOR_SEARCH, filtering = false)).isFalse()
        assertThat(RulesSearchPolicy.showSearch(RulesSearchPolicy.MIN_ROWS_FOR_SEARCH + 1, filtering = false)).isTrue()
        assertThat(RulesSearchPolicy.showSearch(1, filtering = true)).isTrue()
    }

    @Test
    fun chipsListOnlyTheTypesInTheList() {
        val rules = listOf(rule(1, RuleType.KEYWORD), rule(2, RuleType.NUMBER), rule(3, RuleType.NUMBER))
        assertThat(RulesSearchPolicy.chipTypes(rules, null))
            .containsExactly(RuleType.NUMBER, RuleType.KEYWORD)
            .inOrder()
    }

    @Test
    fun oneTypeNeedsNoChipsUnlessAFilterIsActive() {
        val rules = listOf(rule(1, RuleType.KEYWORD), rule(2, RuleType.KEYWORD))
        assertThat(RulesSearchPolicy.chipTypes(rules, null)).isEmpty()
        assertThat(RulesSearchPolicy.chipTypes(rules, RuleType.KEYWORD)).containsExactly(RuleType.KEYWORD)
    }
}
