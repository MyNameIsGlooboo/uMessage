package com.glooboo.umessage.spam.engine

import com.glooboo.umessage.spam.ForwardFormat
import com.glooboo.umessage.spam.ForwardRule
import com.glooboo.umessage.spam.ForwardSim
import com.glooboo.umessage.spam.RuleType
import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Test

class ForwardPlannerTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val slots = mutableMapOf(1 to 11, 2 to 22)
    private val planner = ForwardPlanner(
        matcher = RuleMatcher(NumberNormalizer { "GB" }),
        zone = { zone },
        simInSlot = { slots[it] },
    )

    private fun at(hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 11, 2, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun message(
        address: String = "+447121234567",
        body: String = "Deposit of 500,000 rials",
        sub: Int = 11,
        isMms: Boolean = false,
        time: Long = at(12),
        incoming: Boolean = true,
    ) = ForwardCandidate(address, body, sub, isMms, time, incoming)

    private fun rule(
        id: Long = 1,
        senderType: RuleType? = RuleType.NUMBER,
        sender: String = "07121234567",
        bodyType: RuleType? = null,
        body: String = "",
        targets: List<String> = listOf("+15550001111"),
        includeCodes: Boolean = false,
        enabled: Boolean = true,
        window: Pair<Int, Int>? = null,
        scope: Int? = null,
        sendFrom: ForwardSim = ForwardSim.SAME,
    ) = ForwardRule(
        id = id, senderType = senderType, senderPattern = sender, bodyType = bodyType, bodyPattern = body,
        windowStart = window?.first, windowEnd = window?.second, subscriptionId = scope, targets = targets,
        sendFrom = sendFrom, includeCodes = includeCodes, enabled = enabled,
    )

    private fun plan(message: ForwardCandidate, vararg rules: ForwardRule) =
        planner.plan(message, rules.toList()) { if (it == "+447121234567") "Bank" else it }

    @Test
    fun matchingSender_isForwardedWithTheContactNameAndTheOriginalText() {
        val result = plan(message(), rule())

        assertThat(result.sends).containsExactly(
            PlannedForward(1, "+15550001111", "[from Bank] Deposit of 500,000 rials", 11),
        )
        assertThat(result.skipped).isEmpty()
    }

    @Test
    fun nationalAndInternationalFormsOfTheSenderMatch() {
        assertThat(plan(message(address = "07121234567"), rule(sender = "+447121234567")).sends).hasSize(1)
        assertThat(plan(message(address = "+447121234567"), rule(sender = "0712 123 4567")).sends).hasSize(1)
        assertThat(plan(message(address = "+447130000000"), rule(sender = "07121234567")).sends).isEmpty()
    }

    @Test
    fun prefixAndSenderIdConditions() {
        assertThat(plan(message(), rule(senderType = RuleType.PREFIX, sender = "0712")).sends).hasSize(1)
        assertThat(plan(message(address = "VM-HDFCBK"), rule(senderType = RuleType.SENDER_ID, sender = "HDFCBK")).sends)
            .hasSize(1)
        assertThat(plan(message(address = "AX-PROMO"), rule(senderType = RuleType.SENDER_ID, sender = "HDFCBK")).sends)
            .isEmpty()
    }

    @Test
    fun persianKeywordMatchesArabicLetterFormsAndDigits() {
        // "واريز" written with Arabic yeh in the rule, Persian yeh and Persian digits in the message.
        val r = rule(senderType = null, bodyType = RuleType.KEYWORD, body = "واريز")
        assertThat(plan(message(body = "واریز ۵۰۰٬۰۰۰ ریال به حساب شما"), r).sends).hasSize(1)
        assertThat(plan(message(body = "برداشت از حساب"), r).sends).isEmpty()
    }

    @Test
    fun regexCondition_readsFoldedDigits() {
        val r = rule(senderType = null, bodyType = RuleType.REGEX, body = "مانده[: ]+[0-9]+")
        assertThat(plan(message(body = "مانده: ۱۲۳۴۵"), r).sends).hasSize(1)
    }

    @Test
    fun keywordDoesNotUseTheLeetspeakReadingOfBlockRules() {
        val r = rule(senderType = null, bodyType = RuleType.KEYWORD, body = "free")
        assertThat(plan(message(body = "fr33 tickets"), r).sends).isEmpty()
    }

    @Test
    fun senderAndTextMustBothMatchWhenBothAreSet() {
        val r = rule(bodyType = RuleType.KEYWORD, body = "deposit")
        assertThat(plan(message(body = "Deposit of 10"), r).sends).hasSize(1)
        assertThat(plan(message(body = "Withdrawal of 10"), r).sends).isEmpty()
    }

    @Test
    fun ruleWithoutAnyCondition_neverMatches() {
        assertThat(plan(message(), rule(senderType = null, bodyType = null)).sends).isEmpty()
    }

    @Test
    fun disabledRule_isIgnored() {
        assertThat(plan(message(), rule(enabled = false)).sends).isEmpty()
    }

    @Test
    fun timeWindow_includingOneThatRunsPastMidnight() {
        val day = rule(window = 9 * 60 to 17 * 60)
        assertThat(plan(message(time = at(12)), day).sends).hasSize(1)
        assertThat(plan(message(time = at(17)), day).sends).isEmpty()
        assertThat(plan(message(time = at(8, 59)), day).sends).isEmpty()

        val night = rule(window = 22 * 60 to 6 * 60)
        assertThat(plan(message(time = at(23)), night).sends).hasSize(1)
        assertThat(plan(message(time = at(2)), night).sends).hasSize(1)
        assertThat(plan(message(time = at(12)), night).sends).isEmpty()
    }

    @Test
    fun simScope_onlyMatchesMessagesReceivedOnThatSim() {
        assertThat(plan(message(sub = 11), rule(scope = 22)).sends).isEmpty()
        assertThat(plan(message(sub = 22), rule(scope = 22)).sends).hasSize(1)
    }

    @Test
    fun sendingSim_sameSlotOrFallbackWhenTheSlotIsEmpty() {
        assertThat(plan(message(sub = 11), rule(sendFrom = ForwardSim.SIM2)).sends.single().subscriptionId).isEqualTo(22)
        slots.remove(2)
        val result = plan(message(sub = 11), rule(sendFrom = ForwardSim.SIM2))
        assertThat(result.sends.single().subscriptionId).isEqualTo(11)
        assertThat(result.skipped.map { it.reason }).containsExactly(ForwardSkip.SIM_FALLBACK)
    }

    // region Loop guard

    @Test
    fun aMessageThatIsItselfAForward_isNeverForwarded() {
        val result = plan(message(body = "[from Bank] Deposit of 10"), rule())
        assertThat(result.sends).isEmpty()
        assertThat(result.skipped.map { it.reason }).containsExactly(ForwardSkip.LOOP_FORWARD)
        assertThat(ForwardFormat.isForward("  [from x] y")).isTrue()
    }

    @Test
    fun nothingIsForwardedBackToTheSender() {
        val result = plan(message(), rule(targets = listOf("07121234567", "+15550001111")))
        assertThat(result.sends.map { it.target }).containsExactly("+15550001111")
        assertThat(result.skipped.single().reason).isEqualTo(ForwardSkip.LOOP_SENDER)
    }

    @Test
    fun messagesTheUserSent_areNeverForwarded() {
        assertThat(plan(message(incoming = false), rule())).isEqualTo(ForwardPlan.NONE)
    }

    // endregion

    // region Verification codes

    @Test
    fun codesAreExcludedByDefault() {
        val result = plan(message(body = "Your verification code is 482913"), rule())
        assertThat(result.sends).isEmpty()
        assertThat(result.skipped.map { it.reason }).containsExactly(ForwardSkip.CODE_EXCLUDED)
    }

    @Test
    fun persianCodeIsExcludedToo() {
        assertThat(plan(message(body = "کد تایید شما: ۴۸۲۹۱۳"), rule()).sends).isEmpty()
    }

    @Test
    fun codesAreForwardedOnlyByARuleThatIncludesThem() {
        val result = plan(
            message(body = "Your verification code is 482913"),
            rule(id = 1, targets = listOf("+15550001111")),
            rule(id = 2, targets = listOf("+15550002222"), includeCodes = true),
        )
        assertThat(result.sends.map { it.ruleId to it.target }).containsExactly(2L to "+15550002222")
    }

    // endregion

    @Test
    fun aTargetOfSeveralRulesGetsTheMessageOnce() {
        val result = plan(
            message(body = "Deposit of 10"),
            rule(id = 1, targets = listOf("+15550001111")),
            rule(id = 2, senderType = null, bodyType = RuleType.KEYWORD, body = "deposit", targets = listOf("15550001111", "+15550003333")),
        )
        assertThat(result.sends.map { it.target }).containsExactly("+15550001111", "+15550003333").inOrder()
    }

    @Test
    fun mmsText_isForwardedWithAPictureNote() {
        assertThat(plan(message(isMms = true, body = "Look"), rule()).sends.single().text)
            .isEqualTo("[from Bank] Look [picture not forwarded]")
        assertThat(plan(message(isMms = true, body = ""), rule()).sends.single().text)
            .isEqualTo("[from Bank] [picture not forwarded]")
    }

    @Test
    fun senderDisplayCannotCloseThePrefixEarly() {
        assertThat(ForwardFormat.format("A]B", "hi", isMms = false)).isEqualTo("[from A)B] hi")
    }

    @Test
    fun inWindowEdges() {
        assertThat(ForwardPlanner.inWindow(60, 60, 0)).isTrue()
        assertThat(ForwardPlanner.inWindow(60, 120, 60)).isTrue()
        assertThat(ForwardPlanner.inWindow(60, 120, 120)).isFalse()
    }
}
