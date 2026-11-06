package com.glooboo.umessage.helpers.smartreply.engine

import com.glooboo.umessage.helpers.smartreply.SmartReplyMatcher
import com.glooboo.umessage.helpers.smartreply.SmartReplyTestData
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The engine behind the phrase layer: it only runs when the phrase layer found nothing and no veto word is there. */
class SmartReplyEngineIntegrationTest {

    private val engine = EngineTestData.fixture()
    private val matcher = SmartReplyMatcher(SmartReplyTestData.tables(), engine)
    private val phraseOnly = SmartReplyTestData.matcher()

    @Test
    fun theEngineNeverRunsWhenThePhraseLayerMatched() {
        val text = "Are you coming tonight?"
        assertThat(engine.reply(text, "en")).isNotNull()
        val reply = matcher.match(text, "en")!!
        assertThat(reply.intentId).isEqualTo("are_you_coming")
        assertThat(reply).isEqualTo(phraseOnly.match(text, "en"))
        assertThat(matcher.match("thanks", "en")!!.intentId).isEqualTo("thanks")
    }

    @Test
    fun theEngineAnswersWhenThePhraseLayerFoundNothing() {
        val text = "Do you have my umbrella?"
        assertThat(phraseOnly.match(text, "en")).isNull()
        val reply = matcher.match(text, "en")!!
        assertThat(reply.intentId).isEqualTo("engine_yes_no")
        assertThat(reply.language).isEqualTo("en")
        assertThat(reply.rightToLeft).isFalse()
    }

    @Test
    fun theSeedIsPassedOn() {
        val text = "What time is dinner?"
        val replies = (1L..40L).map { matcher.match(text, "en", it)!!.replies }.toSet()
        assertThat(replies.size).isGreaterThan(1)
        assertThat(matcher.match(text, "en", 5L)).isEqualTo(matcher.match(text, "en", 5L))
    }

    @Test
    fun aVetoWordOfThePhraseTableKeepsTheEngineQuiet() {
        assertThat(engine.reply("Do you have the hospital forms?", "en")).isNotNull()
        assertThat(matcher.match("Do you have the hospital forms?", "en")).isNull()
    }

    @Test
    fun withoutAnEngineTheMatcherIsUnchanged() {
        assertThat(phraseOnly.match("Do you have my umbrella?", "en")).isNull()
        assertThat(phraseOnly.match("Are you coming tonight?", "en")!!.intentId).isEqualTo("are_you_coming")
    }

    @Test
    fun aStatementStaysWithoutChips() {
        assertThat(matcher.match("I have your umbrella.", "en")).isNull()
        assertThat(matcher.match("Thanks, see you at the old market tomorrow, bring the keys", "en")).isNull()
    }
}
