package com.glooboo.umessage.helpers.smartreply.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The gates every text passes before the engine reads it: policy, one question, one script, length. */
class EngineGatesTest {

    private val engine = EngineTestData.fixture()

    private fun reply(text: String) = engine.reply(text, "en")

    @Test
    fun twoQuestionsGiveNothing() {
        assertThat(reply("Are you home? Are you coming?")).isNull()
        assertThat(reply("Are you home? yes")).isNull()
        assertThat(reply("Hi? Are you home?")).isNull()
    }

    @Test
    fun aRunOfQuestionMarksOrTrailingSymbolsIsStillOneQuestion() {
        assertThat(reply("Are you home??")).isNotNull()
        assertThat(reply("Are you home? 😊")).isNotNull()
    }

    @Test
    fun linksAmountsAndLongNumbersGiveNothing() {
        assertThat(reply("Do you like www.shop.com?")).isNull()
        assertThat(reply("Do you have 12345?")).isNull()
        assertThat(reply("Do you have \$20?")).isNull()
    }

    @Test
    fun aLongTextGivesNothing() {
        assertThat(reply("Do you think we should all go to the big old market on the far side of town tomorrow?"))
            .isNull()
        assertThat(reply("Do you think we should all go to the big old market?")).isNotNull()
    }

    @Test
    fun twoScriptsGiveNothing() {
        assertThat(reply("Are you سلام home?")).isNull()
    }

    @Test
    fun blankAndSymbolTextGivesNothing() {
        assertThat(reply("")).isNull()
        assertThat(reply("?")).isNull()
        assertThat(reply("😂😂")).isNull()
    }

    @Test
    fun aTableOnlyReadsItsOwnScript() {
        assertThat(reply("آیا میای؟")).isNull()
    }

    @Test
    fun anEngineWithoutTablesOrBlocksGivesNothing() {
        assertThat(SmartEngine(emptyList()).reply("Are you home?", "en")).isNull()
        val bare = EngineTable(language = "en", script = "latin")
        assertThat(SmartEngine(listOf(bare)).reply("Are you home?", "en")).isNull()
    }

    @Test
    fun twoTablesThatBothAnswerGoToTheAppLanguageOrNothing() {
        val en = EngineTestData.fixtureTable()
        val both = SmartEngine(listOf(en, en.copy(language = "fr")))
        assertThat(both.reply("Are you home?", "en")!!.language).isEqualTo("en")
        assertThat(both.reply("Are you home?", "fr")!!.language).isEqualTo("fr")
        assertThat(both.reply("Are you home?", null)).isNull()
        assertThat(both.reply("Are you home?", "de")).isNull()
        val onlyFrench = SmartEngine(listOf(en.copy(language = "fr"), EngineTable(language = "it", script = "latin")))
        assertThat(onlyFrench.reply("Are you home?", "it")).isNull()
        assertThat(onlyFrench.reply("Are you home?", "fa")!!.language).isEqualTo("fr")
    }
}
