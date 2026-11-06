package com.glooboo.umessage.ui.spam

import com.glooboo.umessage.spam.RuleType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** I22: the rule offered after "Not spam". All numbers, names and texts are invented. */
class SimilarRuleSuggestionTest {

    private fun suggest(address: String, body: String = "ok") = SimilarRuleSuggestion.suggest(address, body)

    @Test
    fun senderIdWithCarrierTagSuggestsTheNameAlone() {
        assertThat(suggest("AX-HDFCBK")).isEqualTo(SimilarRuleSuggestion.Suggestion(RuleType.SENDER_ID, "HDFCBK"))
        assertThat(suggest("vm-shopmart")).isEqualTo(SimilarRuleSuggestion.Suggestion(RuleType.SENDER_ID, "SHOPMART"))
    }

    @Test
    fun senderIdWithoutTagFallsBackToAWordOfTheText() {
        val body = "Your appointment reminder: dentist tomorrow"
        assertThat(suggest("ClinicOne", body))
            .isEqualTo(SimilarRuleSuggestion.Suggestion(RuleType.KEYWORD, "appointment"))
    }

    @Test
    fun rotatingShortCodeSuggestsItsLeadingDigits() {
        assertThat(suggest("300012345")).isNull()
        assertThat(suggest("30001234")).isEqualTo(SimilarRuleSuggestion.Suggestion(RuleType.PREFIX, "300012"))
        assertThat(suggest("300012")).isEqualTo(SimilarRuleSuggestion.Suggestion(RuleType.PREFIX, "3000"))
    }

    @Test
    fun shortNumbersWithNoRoomForAPrefixUseTheText() {
        val body = "Parking permit renewal needed"
        assertThat(suggest("50001", body)).isEqualTo(SimilarRuleSuggestion.Suggestion(RuleType.KEYWORD, "parking"))
    }

    @Test
    fun normalNumbersGetNothing() {
        assertThat(suggest("+14155550133", "Parking permit renewal needed")).isNull()
        assertThat(suggest("07121234567", "Parking permit renewal needed")).isNull()
        assertThat(suggest("+447121234567")).isNull()
        assertThat(suggest("")).isNull()
    }

    @Test
    fun persianTextGivesAFoldedKeyword() {
        val body = "یادآوری نوبت دندانپزشکی شما فردا است"
        assertThat(suggest("ClinicOne", body)).isEqualTo(
            SimilarRuleSuggestion.Suggestion(RuleType.KEYWORD, "دندانپزشکی"),
        )
    }

    @Test
    fun commonShortAndLinkWordsAreNeverChosen() {
        val withLink = "Your code is ready. Visit https://verification-portal.example.test/now"
        assertThat(suggest("ClinicOne", withLink)).isNull()
        assertThat(suggest("ClinicOne", "ok see you")).isNull()
        assertThat(suggest("ClinicOne", "verification payment delivery")).isNull()
    }
}
