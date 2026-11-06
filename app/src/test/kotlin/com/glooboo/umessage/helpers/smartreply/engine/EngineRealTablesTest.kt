package com.glooboo.umessage.helpers.smartreply.engine

import com.glooboo.umessage.helpers.smartreply.SmartReplyMatcher
import com.glooboo.umessage.helpers.smartreply.SmartReplyTestData
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Edge cases found while writing the language files, checked on the shipped tables. Invented texts only. */
class EngineRealTablesTest {

    private val engine = EngineTestData.engine()
    private val matcher = SmartReplyMatcher(SmartReplyTestData.tables(), engine)

    private fun type(text: String, language: String) = engine.reply(text, language)?.intentId

    @Test
    fun aWordThatIsAlsoAQuestionWordIsNotASeparator() {
        assertThat(type("Où es-tu ?", "fr")).isEqualTo("engine_where")
        assertThat(type("Czy przyjdziesz jutro?", "pl")).isEqualTo("engine_yes_no")
        assertThat(type("Czy kawa czy herbata?", "pl")).isEqualTo("engine_choice")
    }

    @Test
    fun anOrNotIsNeverAChoice() {
        assertThat(type("Você vem ou não?", "pt")).isNotEqualTo("engine_choice")
        assertThat(type("Kamu datang atau tidak?", "id")).isNotEqualTo("engine_choice")
        assertThat(type("Przyjdziesz czy nie?", "pl")).isNotEqualTo("engine_choice")
        assertThat(type("Bạn đến hay không?", "vi")).isNotEqualTo("engine_choice")
    }

    @Test
    fun moneyGivesNothing() {
        assertThat(matcher.match("Pode me mandar R$ 80?", "pt")).isNull()
        assertThat(type("Pode me mandar R$ 80?", "pt")).isNull()
    }

    @Test
    fun aWhQuestionEndingInAnEndingWordGetsNoChipsBehindThePhraseLayer() {
        assertThat(matcher.match("Who was right?", "en")).isNull()
    }

    @Test
    fun aShortAcknowledgementGivesNothing() {
        assertThat(type("Öyle mi?", "tr")).isNull()
    }
}
