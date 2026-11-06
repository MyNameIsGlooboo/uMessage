package com.glooboo.umessage.ui.spam

import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Pure logic added for design review 2: sender names, Persian digits, rule test, forward target and breadth checks. */
class RuleReviewLogicTest {

    private fun validate(type: RuleType, raw: String, kind: ListKind =
        ListKind.BLOCK) = RuleValidator.validate(kind, type, raw)

    // region Sender names (BR-47)

    @Test
    fun senderNameWithASpaceIsValid() {
        assertThat(validate(RuleType.SENDER_ID, "Bank Melli")).isEqualTo(RuleValidation.Valid("Bank Melli"))
    }

    @Test
    fun persianSenderNameIsValid() {
        assertThat(validate(RuleType.SENDER_ID, "بانک ملی")).isEqualTo(RuleValidation.Valid("بانک ملی"))
    }

    @Test
    fun senderNameNamesTheCharacterItCannotUse() {
        val result = validate(RuleType.SENDER_ID, "Bank#1") as RuleValidation.Invalid
        assertThat(result.error).isEqualTo(RuleError.SENDER_ID)
        assertThat(result.detail).isEqualTo("#")
    }

    @Test
    fun senderNameNeedsALetterAndHasALimit() {
        val noLetter = validate(RuleType.SENDER_ID, "123 456") as RuleValidation.Invalid
        assertThat(noLetter.error).isEqualTo(RuleError.SENDER_ID)
        assertThat(noLetter.detail).isNull()
        val tooLong = validate(RuleType.SENDER_ID, "A".repeat(RuleValidator.MAX_SENDER_ID + 1))
            as RuleValidation.Invalid
        assertThat(tooLong.error).isEqualTo(RuleError.TOO_LONG)
    }

    @Test
    fun senderNameAtTheLimitIsValid() {
        assertThat(validate(RuleType.SENDER_ID, "A".repeat(RuleValidator.MAX_SENDER_ID)))
            .isInstanceOf(RuleValidation.Valid::class.java)
    }

    // endregion

    // region Numbers (BR-45)

    @Test
    fun persianDigitsInANumberAreReadAsAscii() {
        assertThat(validate(RuleType.NUMBER, "۰۷۱۲ ۱۲۳ ۴۵۶۷")).isEqualTo(RuleValidation.Valid("07121234567"))
        assertThat(validate(RuleType.PREFIX, "+۴۴ ۷۱۲")).isEqualTo(RuleValidation.Valid("+44712"))
    }

    @Test
    fun shortCodesStillWorkAsNumbers() {
        assertThat(validate(RuleType.NUMBER, "3000")).isEqualTo(RuleValidation.Valid("3000"))
    }

    // endregion

    // region Test a rule on a sample (BR-46)

    @Test
    fun keywordTestMatchesOrNot() {
        assertThat(RuleTest.run(ListKind.BLOCK, RuleType.KEYWORD, "free gift", "You won a FREE gift today"))
            .isEqualTo(RuleTestResult.MATCH)
        assertThat(RuleTest.run(ListKind.BLOCK, RuleType.KEYWORD, "free gift", "See you at eight"))
            .isEqualTo(RuleTestResult.NO_MATCH)
    }

    @Test
    fun regexTestMatchesOrNot() {
        assertThat(RuleTest.run(ListKind.BLOCK, RuleType.REGEX, "win\\s+\\d+", "you win 500 now"))
            .isEqualTo(RuleTestResult.MATCH)
        assertThat(RuleTest.run(ListKind.BLOCK, RuleType.REGEX, "win\\s+\\d+", "winter is coming"))
            .isEqualTo(RuleTestResult.NO_MATCH)
    }

    @Test
    fun brokenOrUnsupportedRulesCannotBeTried() {
        assertThat(RuleTest.run(ListKind.BLOCK, RuleType.REGEX, "(", "anything")).isEqualTo(RuleTestResult.UNUSABLE)
        assertThat(RuleTest.run(ListKind.BLOCK, RuleType.NUMBER, "+447121234567", "anything"))
            .isEqualTo(RuleTestResult.UNUSABLE)
        assertThat(RuleTest.supports(RuleType.PREFIX)).isFalse()
        assertThat(RuleTest.supports(RuleType.KEYWORD)).isTrue()
    }

    // endregion

    // region Forward targets (BR-35, BR-36)

    @Test
    fun forwardTargetReadsPersianDigits() {
        assertThat(ForwardTargets.clean("۰۷۱۲۱۲۳۴۵۶۷")).isEqualTo("07121234567")
    }

    @Test
    fun forwardingToAnOwnNumberIsRefusedInEitherNotation() {
        val errors = ForwardTargets.errors(listOf("07121234567", "+15550001111"), ownNumbers = listOf("+447121234567"))
        assertThat(errors).containsExactly(TargetError.OWN_NUMBER, null).inOrder()
    }

    @Test
    fun forwardingToTheWatchedSenderIsRefused() {
        val form = ForwardForm(
            senderType = RuleType.NUMBER,
            senderPattern = "07121234567",
            targets = listOf("+447121234567"),
        )
        assertThat(form.targetErrors).containsExactly(TargetError.SAME_AS_SENDER)
        assertThat(form.canSubmit).isFalse()
    }

    @Test
    fun forwardingToAnotherNumberIsFine() {
        val form = ForwardForm(
            senderType = RuleType.NUMBER, senderPattern = "07121234567", targets = listOf("+15550001111"),
            ownNumbers = listOf("+447350000000"),
        )
        assertThat(form.targetErrors).containsExactly(null)
        assertThat(form.canSubmit).isTrue()
    }

    @Test
    fun shortCodesAreNotForwardTargets() {
        assertThat(ForwardTargets.errors(listOf("3000"))).containsExactly(TargetError.INVALID)
    }

    @Test
    fun costEstimateCountsTheNumbers() {
        assertThat(ForwardForm(targets = listOf("")).minSmsPerForward).isEqualTo(1)
        assertThat(ForwardForm(targets = listOf("+15550001111", "+15550002222")).minSmsPerForward).isEqualTo(2)
        // The same number typed twice is sent to once.
        assertThat(ForwardForm(targets = listOf("+15550001111", "15550001111")).minSmsPerForward).isEqualTo(1)
    }

    // endregion

    // region Forward breadth (BR-34)

    private fun form(
        sender: RuleType?, senderPattern: String = "", body: RuleType? = null, bodyPattern: String = "",
    ) = ForwardForm(
        senderType = sender, senderPattern = senderPattern, bodyType = body, bodyPattern = bodyPattern,
        targets = listOf("+15550001111"),
    )

    @Test
    fun aRuleWithNeitherSenderNorTextIsNeverValid() {
        val f = form(sender = null)
        assertThat(f.noCondition).isTrue()
        assertThat(f.isValid).isFalse()
        assertThat(f.isBroad).isFalse()
    }

    @Test
    fun anySenderWithAVeryShortTextIsBroad() {
        assertThat(form(sender = null, body = RuleType.KEYWORD, bodyPattern = "win").isBroad).isTrue()
        assertThat(form(sender = null, body = RuleType.REGEX, bodyPattern = "[a-z]").isBroad).isTrue()
    }

    @Test
    fun anySenderWithASpecificTextIsNotBroad() {
        assertThat(form(sender = null, body = RuleType.KEYWORD, bodyPattern = "bank alert").isBroad).isFalse()
    }

    @Test
    fun aVeryShortSenderPrefixAloneIsBroad() {
        assertThat(form(sender = RuleType.PREFIX, senderPattern = "+9").isBroad).isTrue()
        assertThat(form(sender = RuleType.PREFIX, senderPattern = "+44712").isBroad).isFalse()
        // A text condition narrows it down again.
        val prefixAndText = form(
            sender = RuleType.PREFIX,
            senderPattern = "+9",
            body = RuleType.KEYWORD,
            bodyPattern = "bank alert",
        )
        assertThat(prefixAndText.isBroad).isFalse()
    }

    @Test
    fun aSpecificNumberIsNotBroad() {
        assertThat(form(sender = RuleType.NUMBER, senderPattern = "07121234567").isBroad).isFalse()
    }

    @Test
    fun senderErrorsCarryTheBadCharacter() {
        val f = form(sender = RuleType.SENDER_ID, senderPattern = "Bank/1")
        assertThat(f.senderError).isEqualTo(RuleError.SENDER_ID)
        assertThat(f.senderInvalid?.detail).isEqualTo("/")
    }

    // endregion

    @Test
    fun moveToRulesCountsOnlyNumbersWithoutAMatchingRule() {
        val numbers = listOf(
            BlockedNumberItem(1, "5559990"),
            BlockedNumberItem(2, "30002"),
            BlockedNumberItem(3, "5551234"),
            BlockedNumberItem(4, "+44*12*"),
        )
        val rules = listOf(
            SpamRule(kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = "5559990"),
            SpamRule(kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = "30002"),
        )
        // 5559990 and 30002 are already uMessage rules; the mid-pattern wildcard cannot be one.
        assertThat(LegacyMigration.movableCount(numbers, rules)).isEqualTo(1)
        assertThat(LegacyMigration.movableCount(numbers.take(2), rules)).isEqualTo(0)
        assertThat(LegacyMigration.movableCount(numbers, emptyList())).isEqualTo(3)
    }
}
