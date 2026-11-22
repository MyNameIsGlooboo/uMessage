package com.glooboo.umessage.ui.main

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** T-2: the Spam tab's "View rule" goes to the block list, its menu's Rules to the rules screen as it opens. */
class SpamRuleLinksTest {

    private val opened = mutableListOf<String>()

    private val navigation = MainNavigation(
        onSpamRules = { opened += "rules" },
        onSpamBlockRules = { opened += "block rules" },
        onManageForwardRules = { opened += "forwarding" },
    )

    @Test
    fun viewRuleOpensTheBlockList() {
        SpamRuleLinks(navigation).onViewRule()
        assertThat(opened).containsExactly("block rules")
    }

    @Test
    fun theMenusRulesOpensTheRulesScreen() {
        SpamRuleLinks(navigation).onOpenRules()
        assertThat(opened).containsExactly("rules")
    }
}
