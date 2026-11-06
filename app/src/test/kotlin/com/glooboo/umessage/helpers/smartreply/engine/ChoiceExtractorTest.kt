package com.glooboo.umessage.helpers.smartreply.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Rule 5 for CHOICE: two clean options or nothing. Invented texts only. */
class ChoiceExtractorTest {

    private val engine = EngineTestData.fixture()

    private fun options(text: String) = engine.reply(text, "en")?.replies?.take(2)

    @Test
    fun twoOptionsAreCapitalisedAndAnEitherChipFollows() {
        val reply = engine.reply("tea or coffee?", "en")!!
        assertThat(reply.intentId).isEqualTo("engine_choice")
        assertThat(reply.replies.take(2)).containsExactly("Tea", "Coffee").inOrder()
        assertThat(reply.replies).hasSize(3)
        assertThat(reply.replies[2]).isEqualTo("Either is fine")
    }

    @Test
    fun questionWordsAndFillersAreStripped() {
        assertThat(options("Do you want tea or coffee?")).containsExactly("Tea", "Coffee").inOrder()
        assertThat(options("Can you do pizza or pasta?")).containsExactly("Pizza", "Pasta").inOrder()
    }

    @Test
    fun accentsAreKeptAsTyped() {
        assertThat(options("Do you want café or thé?")).containsExactly("Café", "Thé").inOrder()
    }

    @Test
    fun anOptionMayHaveUpToMaxOptionWords() {
        assertThat(options("Red wine or the white one?")).containsExactly("Red wine", "The white one").inOrder()
        assertThat(options("Red wine or the very old white wine?")).isNull()
    }

    @Test
    fun threeOptionsGiveNothing() {
        assertThat(options("Tea or coffee or juice?")).isNull()
        assertThat(options("Tea, coffee or juice?")).isNull()
        assertThat(options("Do you want tea, coffee or juice")).isNull()
    }

    @Test
    fun optionsThatCannotBePulledCleanlyGiveNothing() {
        assertThat(options("Or tea?")).isNull()
        assertThat(options("Tea or tea?")).isNull()
        assertThat(options("Are you coming to the party tonight or are you staying home?")).isNull()
    }

    @Test
    fun aSeparatorWithNoOptionOnOneSideFallsThroughToTheOtherTypes() {
        assertThat(engine.reply("Or where is it?", "en")!!.intentId).isEqualTo("engine_where")
        assertThat(engine.reply("Can you or?", "en")!!.intentId).isEqualTo("engine_yes_no")
    }

    @Test
    fun aStarterIsNotPartOfTheFirstOptionWithoutAQuestionMark() {
        assertThat(options("Do you want tea or coffee")).containsExactly("Tea", "Coffee").inOrder()
        assertThat(options("Are you tea or coffee")).containsExactly("Tea", "Coffee").inOrder()
    }

    @Test
    fun aCompactScriptSplitsOnItsSeparator() {
        val zh = EngineTable(
            language = "zh",
            script = "han",
            maxWords = 30,
            yesNo = YesNoBlock(starters = listOf("你要", "你想"), endings = listOf("吗")),
            choice = ChoiceBlock(separators = listOf("还是"), maxOptionWords = 4, either = listOf("都可以")),
        )
        val zhEngine = SmartEngine(listOf(zh))
        assertThat(zhEngine.reply("你要茶还是咖啡？", "zh")!!.replies).containsExactly("茶", "咖啡", "都可以").inOrder()
        assertThat(zhEngine.reply("你要茶还是咖啡还是果汁？", "zh")).isNull()
    }
}
