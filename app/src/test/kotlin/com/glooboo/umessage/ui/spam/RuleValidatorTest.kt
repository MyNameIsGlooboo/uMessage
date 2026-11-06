package com.glooboo.umessage.ui.spam

import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RuleValidatorTest {

    private fun error(type: RuleType, raw: String, kind: ListKind = ListKind.BLOCK) =
        (RuleValidator.validate(kind, type, raw) as? RuleValidation.Invalid)?.error

    @Test
    fun nestedRepeatRegexIsRefusedLikeTheRepositoryDoes() {
        // T11 / SE-14 / SU-01: the form said "valid", the repository then threw and the rule vanished.
        assertThat(error(RuleType.REGEX, "(a+)+")).isEqualTo(RuleError.REGEX_UNSAFE)
        assertThat(error(RuleType.REGEX, "(\\w+\\s?)*$")).isEqualTo(RuleError.REGEX_UNSAFE)
    }

    @Test
    fun catchAllRegexIsRefused() {
        // SU-05: "." matches every real message, yet not the empty string.
        assertThat(error(RuleType.REGEX, ".")).isEqualTo(RuleError.REGEX_MATCHES_ALL)
        assertThat(error(RuleType.REGEX, "^")).isEqualTo(RuleError.REGEX_MATCHES_EMPTY)
        assertThat(error(RuleType.REGEX, ".*")).isEqualTo(RuleError.REGEX_MATCHES_EMPTY)
        assertThat(error(RuleType.REGEX, "[\\s\\S]")).isEqualTo(RuleError.REGEX_MATCHES_ALL)
    }

    @Test
    fun ordinaryRegexIsValid() {
        assertThat(RuleValidator.validate(ListKind.BLOCK, RuleType.REGEX, "win\\s+\\d+"))
            .isEqualTo(RuleValidation.Valid("win\\s+\\d+"))
    }

    @Test
    fun broadRegexIsWarned() {
        val warnings = RuleValidator.warnings(ListKind.BLOCK, RuleType.REGEX, "[a-z]", emptyList(), null)
        assertThat(warnings).contains(RuleWarning.BROAD)
    }

    @Test
    fun numberWithLettersCanNeverMatchAndIsRefused() {
        assertThat(error(RuleType.NUMBER, "+44712abc")).isEqualTo(RuleError.NUMBER)
        assertThat(error(RuleType.PREFIX, "VM-")).isEqualTo(RuleError.PREFIX)
        assertThat(error(RuleType.SENDER_ID, "12345")).isEqualTo(RuleError.SENDER_ID)
    }

    @Test
    fun shortAllowKeywordIsRefused() {
        // SE-08 / SU-03: an allowed word skips every block, so very short ones are not accepted.
        assertThat(error(RuleType.KEYWORD, "کد", ListKind.ALLOW)).isEqualTo(RuleError.ALLOW_KEYWORD_SHORT)
        assertThat(error(RuleType.KEYWORD, "ok", ListKind.ALLOW)).isEqualTo(RuleError.ALLOW_KEYWORD_SHORT)
        // As a block keyword two characters are still allowed (with a warning).
        assertThat(error(RuleType.KEYWORD, "ok", ListKind.BLOCK)).isNull()
    }

    @Test
    fun everyAllowTextRuleNeedsConfirmationAndCommonWordsAreFlagged() {
        assertThat(RuleValidator.warnings(ListKind.ALLOW, RuleType.KEYWORD, "delivery code", emptyList(), null))
            .containsExactly(RuleWarning.ALLOW_SKIPS_BLOCKS)
        assertThat(RuleValidator.warnings(ListKind.ALLOW, RuleType.KEYWORD, "Bank", emptyList(), null))
            .containsExactly(RuleWarning.ALLOW_SKIPS_BLOCKS, RuleWarning.COMMON_WORD)
        assertThat(RuleValidator.warnings(ListKind.ALLOW, RuleType.KEYWORD, "پرداخت", emptyList(), null))
            .containsExactly(RuleWarning.ALLOW_SKIPS_BLOCKS, RuleWarning.COMMON_WORD)
        assertThat(RuleValidator.warnings(ListKind.ALLOW, RuleType.SENDER_ID, "VM-HDFCBK", emptyList(), null)).isEmpty()
    }

    @Test
    fun shortBlockKeywordIsWarned() {
        // SU-07: keywords match inside longer words, so "win" also hits "window".
        assertThat(RuleValidator.warnings(ListKind.BLOCK, RuleType.KEYWORD, "win", emptyList(), null))
            .containsExactly(RuleWarning.BROAD)
        assertThat(RuleValidator.warnings(ListKind.BLOCK, RuleType.KEYWORD, "free gift", emptyList(), null)).isEmpty()
    }

    @Test
    fun blockOfAnAllowedNumberIsWarned() {
        // SU-04: the allow rule wins, so this block would never apply.
        val existing = listOf(
            SpamRule(1, ListKind.ALLOW, RuleType.NUMBER, "+447121112222"),
            SpamRule(2, ListKind.ALLOW, RuleType.PREFIX, "+44735"),
        )
        assertThat(RuleValidator.warnings(ListKind.BLOCK, RuleType.NUMBER, "+447121112222", existing, null))
            .containsExactly(RuleWarning.ALLOWED_CONFLICT)
        assertThat(RuleValidator.conflictingAllows(existing, RuleType.NUMBER, "07121112222", null).map { it.id })
            .containsExactly(1L)
        assertThat(RuleValidator.conflictingAllows(existing, RuleType.NUMBER, "+447350000000", null).map { it.id })
            .containsExactly(2L)
        assertThat(RuleValidator.conflictingAllows(existing, RuleType.NUMBER, "+447190000000", null)).isEmpty()
    }

    @Test
    fun allowOnOneSimDoesNotConflictWithBlockOnTheOther() {
        val existing = listOf(SpamRule(1, ListKind.ALLOW, RuleType.NUMBER, "+447121112222", subscriptionId = 1))
        assertThat(RuleValidator.conflictingAllows(existing, RuleType.NUMBER, "+447121112222", 2)).isEmpty()
        // A block on both SIMs still works on SIM 2.
        assertThat(RuleValidator.conflictingAllows(existing, RuleType.NUMBER, "+447121112222", null)).isEmpty()
        assertThat(RuleValidator.conflictingAllows(existing, RuleType.NUMBER, "+447121112222", 1)).hasSize(1)
        val everywhere = listOf(SpamRule(1, ListKind.ALLOW, RuleType.NUMBER, "+447121112222"))
        assertThat(RuleValidator.conflictingAllows(everywhere, RuleType.NUMBER, "+447121112222", 2)).hasSize(1)
    }

    @Test
    fun duplicateIgnoresCase() {
        val existing = listOf(SpamRule(1, ListKind.BLOCK, RuleType.KEYWORD, "Free Gift"))
        assertThat(RuleValidator.isDuplicate(existing, ListKind.BLOCK, RuleType.KEYWORD, "free gift", null)).isTrue()
        assertThat(RuleValidator.isDuplicate(existing, ListKind.ALLOW, RuleType.KEYWORD, "free gift", null)).isFalse()
        assertThat(RuleValidator.isDuplicate(existing, ListKind.BLOCK, RuleType.KEYWORD, "free gift", 2)).isFalse()
    }
}
