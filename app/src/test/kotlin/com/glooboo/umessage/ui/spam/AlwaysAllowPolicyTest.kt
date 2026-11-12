package com.glooboo.umessage.ui.spam

import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** spamui-21: when the Why-blocked dialog offers "Always allow". */
class AlwaysAllowPolicyTest {

    private val number = "+14155550133"

    @Test
    fun aNumberWithoutAnAllowRuleIsOffered() {
        assertThat(AlwaysAllowPolicy.canOffer(emptyList(), number)).isTrue()
        val block = SpamRule(1, ListKind.BLOCK, RuleType.NUMBER, number)
        assertThat(AlwaysAllowPolicy.canOffer(listOf(block), number)).isTrue()
    }

    @Test
    fun aSenderThatIsAllowedAlreadyIsNotOffered() {
        val allow = SpamRule(2, ListKind.ALLOW, RuleType.NUMBER, number)
        assertThat(AlwaysAllowPolicy.canOffer(listOf(allow), number)).isFalse()
    }

    @Test
    fun aNameAnyoneCanFakeIsNeverOffered() {
        assertThat(AlwaysAllowPolicy.canOffer(emptyList(), "BANK-X")).isFalse()
    }
}
