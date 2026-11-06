package com.glooboo.umessage.spam.engine

import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** ID-33 / ID-34: rule values match whatever way the Persian letters, digits and spaces are typed. */
class PersianRuleMatchTest {
    private val matcher = RuleMatcher(NumberNormalizer { "GB" })
    private fun msg(address: String = "+447700900155", body: String = "x") = IncomingMessage(address, body, 1)
    private fun rule(type: RuleType, pattern: String) = SpamRule(kind = ListKind.BLOCK, type = type, pattern = pattern)

    @Test fun persianDigitsAndSpacesMatchNumber() {
        val persian = "۰۷۷۰۰ ۹۰۰ ۱۵۵"
        assertThat(matcher.matches(rule(RuleType.NUMBER, persian), msg())).isTrue()
        assertThat(matcher.matches(rule(RuleType.NUMBER, "+44 7700 900 155"), msg("07700900155"))).isTrue()
        assertThat(matcher.matches(rule(RuleType.NUMBER, "0044 7700-900-155"), msg())).isTrue()
    }

    @Test fun letterVariantsAndHalfSpaceMatchKeyword() {
        val body = "با تخفیف یا کارت"
        assertThat(matcher.matches(rule(RuleType.KEYWORD, "تخفيف"), msg(body = body))).isTrue()
        assertThat(matcher.matches(rule(RuleType.KEYWORD, "كارت"), msg(body = body))).isTrue()
        assertThat(matcher.matches(rule(RuleType.KEYWORD, "با‌ تخفیف"), msg(body = body))).isTrue()
    }

    @Test fun latinKeywordsBehaveAsBefore() {
        assertThat(matcher.matches(rule(RuleType.KEYWORD, "free"), msg(body = "Get FREE stuff"))).isTrue()
        assertThat(matcher.matches(rule(RuleType.KEYWORD, "free"), msg(body = "Get fine stuff"))).isFalse()
    }
}
