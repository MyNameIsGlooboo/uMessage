package com.glooboo.umessage.spam.engine

import com.glooboo.umessage.helpers.CodeMasker
import com.glooboo.umessage.spam.ForwardRule
import com.glooboo.umessage.spam.RuleType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ForwardPlannerMaskTest {
    private val rule = ForwardRule(
        id = 1, senderType = RuleType.NUMBER, senderPattern = "07121234567", targets = listOf("+15550001111"),
        includeCodes = true,
    )
    private val message = ForwardCandidate("+447121234567", "Your code is 482913", 11)

    private fun planner(mask: () -> ((String) -> String)?) = ForwardPlanner(
        matcher = RuleMatcher(NumberNormalizer { "GB" }),
        codeMask = mask,
    )

    @Test fun codeIsForwardedInFullWithoutMask() {
        val plan = planner { null }.plan(message, listOf(rule)) { it }
        assertThat(plan.sends.single().text).contains("482913")
    }

    @Test fun codeIsMaskedWhenMaskIsOn() {
        val masker: (String) -> String = { CodeMasker.mask(it, false, CodeMasker.ASTERISKS) }
        val plan = planner { masker }.plan(message, listOf(rule)) { it }
        val text = plan.sends.single().text
        assertThat(text).contains("****")
        assertThat(text).doesNotContain("482913")
    }

    @Test fun ordinaryMessageIsNotTouched() {
        val plain = message.copy(body = "Meeting at 5")
        val masker: (String) -> String = { "MASKED" }
        val plan = planner { masker }.plan(plain, listOf(rule)) { it }
        assertThat(plan.sends.single().text).contains("Meeting at 5")
    }

    /** M3: a code the masker cannot locate ("482913G" ends in a letter) is still hidden, never forwarded in clear. */
    @Test fun maskingFailsClosedWhenTheCodeIsNotLocated() {
        val glued = message.copy(body = "Your verification code is 482913G")
        val masker: (String) -> String = { CodeMasker.mask(it, false, CodeMasker.ASTERISKS) }
        val text = planner { masker }.plan(glued, listOf(rule)) { it }.sends.single().text
        assertThat(text).doesNotContain("482913")
        assertThat(text).contains("****")
    }

    @Test fun aMaskerThatThrowsAlsoFailsClosed() {
        val text = planner { { _: String -> error("broken") } }.plan(message, listOf(rule)) { it }.sends.single().text
        assertThat(text).doesNotContain("482913")
    }

    /** A code the "Copy code" extractor finds but the spam detector does not is still a code for forwarding. */
    @Test fun aCodeOnlyTheExtractorFindsIsLeftOutByARuleWithoutCodes() {
        val spaced = message.copy(body = "bank verification code is 48 29 13 ref 7766554")
        val plan = planner { null }.plan(spaced, listOf(rule.copy(includeCodes = false))) { it }
        assertThat(plan.sends).isEmpty()
        assertThat(plan.skipped.single().reason).isEqualTo(ForwardSkip.CODE_EXCLUDED)
    }

    @Test fun spacedCodeAndTheExtractorsCodeAreBothMasked() {
        val spaced = message.copy(body = "bank verification code is 48 29 13 ref 7766554")
        val masker: (String) -> String = { CodeMasker.mask(it, false, CodeMasker.ASTERISKS) }
        val text = planner { masker }.plan(spaced, listOf(rule)) { it }.sends.single().text
        assertThat(text).doesNotContain("48 29 13")
        assertThat(text).doesNotContain("7766554")
        assertThat(text).contains("bank verification code is **** ref ****")
    }

    @Test fun looksLikeCodeTakesEitherReading() {
        assertThat(ForwardPlanner.looksLikeCode("Your OTP is 4821")).isTrue()
        assertThat(ForwardPlanner.looksLikeCode("bank verification code is 48 29 13 ref 7766554")).isTrue()
        assertThat(ForwardPlanner.looksLikeCode("Meeting at 5, room 12")).isFalse()
    }
}
