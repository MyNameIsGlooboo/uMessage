package com.glooboo.umessage.ui.spam

import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** Pure parts of the design review 2 fixes on the Blocked tab and the Rules editors. */
class BlockedReview2LogicTest {

    private val day = 24L * 60 * 60 * 1000

    // BR-10: the restored list

    @Test
    fun restoredLogRoundTripsAndDropsBrokenLines() {
        val entries = listOf(RestoredEntry("+447121234567", 1_000, 2_000), RestoredEntry("BANK\tX", 3_000, 4_000))
        val decoded = RestoredLogCodec.decode(RestoredLogCodec.encode(entries) + "\nnot a line\n1\t2")
        assertThat(decoded.map { it.address }).containsExactly("+447121234567", "BANK X").inOrder()
        assertThat(decoded.first().restoredAt).isEqualTo(2_000)
    }

    @Test
    fun restoredLogKeepsTheNewestWithinThirtyDays() {
        val now = 100 * day
        val old = RestoredEntry("old", 0, now - 31 * day)
        val kept = RestoredEntry("kept", 0, now - 2 * day)
        val added = RestoredEntry("new", 0, now)
        val merged = RestoredLogCodec.merge(listOf(kept, old), listOf(added), now, RestoredLog.KEEP_MS, max = 100)
        assertThat(merged.map { it.address }).containsExactly("new", "kept").inOrder()
        assertThat(RestoredLogCodec.merge(merged, emptyList(), now, RestoredLog.KEEP_MS, max = 1)).hasSize(1)
    }

    @Test
    fun restoredLogWithoutStorageStillWorks() {
        val log = RestoredLog(prefs = null, now = { 10 * day })
        log.record(listOf(RestoredEntry("+44712", 9 * day, 10 * day)))
        assertThat(log.entries.value.single().address).isEqualTo("+44712")
    }

    // BR-17: day headers

    @Test
    fun groupsAreSplitByTheDayOfTheirNewestMessage() {
        val today = LocalDate.of(2026, 11, 2).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        fun group(address: String, at: Long) = SenderGroup(
            address,
            listOf(BlockedMessage(at, address, "x", 1, false, at, DecisionReason.UNKNOWN_SENDER)),
        )
        val groups = listOf(group("a", today + 5_000), group("b", today + 1_000), group("c", today - 1_000))
        val days = groupByDay(groups, ZoneOffset.UTC)
        assertThat(days.map { it.day }).containsExactly(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 1)).inOrder()
        assertThat(days.first().groups.map { it.address }).containsExactly("a", "b").inOrder()
    }

    // BR-28 / bug 15: short codes on the Android list

    @Test
    fun shortCodesAndBulkNumbersAreRecognised() {
        assertThat(BlockedNumberFormat.isShortCode("30002")).isTrue()
        assertThat(BlockedNumberFormat.isShortCode("3000123456")).isTrue()
        assertThat(BlockedNumberFormat.isShortCode("1000 1234")).isTrue()
        assertThat(BlockedNumberFormat.isShortCode("07121234567")).isFalse()
        assertThat(BlockedNumberFormat.isShortCode("+447121234567")).isFalse()
        assertThat(BlockedNumberFormat.isShortCode("+44712*")).isFalse()
        assertThat(BlockedNumberFormat.isShortCode("12")).isFalse()
    }

    @Test
    fun sevenAndEightDigitNumbersAreNotShortCodes() {
        assertThat(BlockedNumberFormat.isShortCode("123")).isTrue()
        assertThat(BlockedNumberFormat.isShortCode("123456")).isTrue()
        assertThat(BlockedNumberFormat.isShortCode("5559990")).isFalse()
        assertThat(BlockedNumberFormat.isShortCode("555-9990")).isFalse()
        assertThat(BlockedNumberFormat.isShortCode("22345678")).isFalse()
    }

    // ID-41: pattern building blocks

    @Test
    fun patternBlocksAreValidPatternsAndAreRecognisedAgain() {
        listOf(PatternBlock.LINK, PatternBlock.LONG_NUMBER, PatternBlock.ANY_OF).forEach { block ->
            val result = RuleValidator.validate(ListKind.BLOCK, RuleType.REGEX, block.template)
            assertThat(result).isInstanceOf(RuleValidation.Valid::class.java)
            assertThat(PatternBlock.of(block.template)).isEqualTo(block)
        }
        assertThat(RuleTest.run(ListKind.BLOCK, RuleType.REGEX, PatternBlock.LINK.template, "see www.example.test"))
            .isEqualTo(RuleTestResult.MATCH)
        assertThat(RuleTest.run(ListKind.BLOCK, RuleType.REGEX, PatternBlock.LONG_NUMBER.template, "code 1234"))
            .isEqualTo(RuleTestResult.NO_MATCH)
    }

    @Test
    fun startsWithKeepsWhatWasTypedAfterTheCaret() {
        assertThat(PatternBlock.apply(PatternBlock.STARTS_WITH, "")).isEqualTo("^")
        assertThat(PatternBlock.apply(PatternBlock.STARTS_WITH, "^Bank")).isEqualTo("^Bank")
        assertThat(PatternBlock.apply(PatternBlock.STARTS_WITH, PatternBlock.LINK.template)).isEqualTo("^")
        assertThat(PatternBlock.of("^Bank")).isEqualTo(PatternBlock.STARTS_WITH)
        assertThat(PatternBlock.of("(win|prize)")).isEqualTo(PatternBlock.ANY_OF)
        assertThat(PatternBlock.of("win\\s+\\d+")).isNull()
    }

    // ID-53: concrete examples

    @Test
    fun concreteExamplesAreValidRules() {
        assertThat(RuleValidator.validate(ListKind.BLOCK, RuleType.KEYWORD, RuleExamples.CANCEL_WORD))
            .isInstanceOf(RuleValidation.Valid::class.java)
        assertThat(RuleValidator.validate(ListKind.BLOCK, RuleType.PREFIX, RuleExamples.AD_PREFIX))
            .isInstanceOf(RuleValidation.Valid::class.java)
    }
}
