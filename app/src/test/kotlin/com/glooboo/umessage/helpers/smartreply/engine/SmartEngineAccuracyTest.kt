package com.glooboo.umessage.helpers.smartreply.engine

import com.glooboo.umessage.helpers.smartreply.SmartReplyTestData
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * Precision gate of the question engine, per language, on the labelled sets in
 * `src/test/resources/smart_engine/eval_engine_<language>.txt` (invented texts). All tables are loaded, as in the
 * app. A NONE line must show nothing, no line may get another type, a CHOICE line must give its two options, and
 * coverage has to stay at or above [MIN_RECALL].
 */
class SmartEngineAccuracyTest {

    private val engine = EngineTestData.engine()
    private val phraseOnly = SmartReplyTestData.matcher()

    /** Lines the phrase layer answers are not the engine's: it only ever runs behind that layer. */
    private fun reply(text: String, language: String) = engine.reply(text, language)

    private fun check(language: String) {
        val set = EngineTestData.labelled(language).filter { phraseOnly.match(it.text, language) == null }
        val positives = set.filterNot { it.expectsNothing }
        val negatives = set.filter { it.expectsNothing }
        assertWithMessage("$language positives").that(positives.size).isAtLeast(MIN_SET)
        assertWithMessage("$language negatives").that(negatives.size).isAtLeast(MIN_SET)

        val falseShows = negatives.filter { reply(it.text, language) != null }
        assertWithMessage("$language: chips shown where none belong").that(falseShows.map { it.text }).isEmpty()

        val shown = positives.map { it to reply(it.text, language) }
        val wrong = shown.filter { (labelled, reply) -> reply != null && reply.intentId != labelled.intentId }
        assertWithMessage("$language: wrong type").that(wrong.map { it.first.text to it.second?.intentId }).isEmpty()

        val missed = shown.filterNot { (labelled, reply) -> reply != null && sameOptions(labelled, reply.replies) }
        val hits = positives.size - missed.size
        val recall = hits.toDouble() / positives.size
        assertWithMessage("$language recall $hits of ${positives.size}, missed ${missed.map { it.first.text }}")
            .that(recall).isAtLeast(MIN_RECALL)
    }

    private fun sameOptions(labelled: EngineTestData.Labelled, replies: List<String>): Boolean =
        labelled.options.isEmpty() || replies.take(2).map(EngineTestData::normal) == labelled.options

    @Test
    fun english() = check("en")

    @Test
    fun persian() = check("fa")

    @Test
    fun turkish() = check("tr")

    @Test
    fun arabic() = check("ar")

    @Test
    fun chinese() = check("zh")

    @Test
    fun japanese() = check("ja")

    @Test
    fun korean() = check("ko")

    @Test
    fun thai() = check("th")

    @Test
    fun bengali() = check("bn")

    @Test
    fun hindi() = check("hi")

    @Test
    fun russian() = check("ru")

    @Test
    fun ukrainian() = check("uk")

    @Test
    fun french() = check("fr")

    @Test
    fun german() = check("de")

    @Test
    fun spanish() = check("es")

    @Test
    fun italian() = check("it")

    @Test
    fun portuguese() = check("pt")

    @Test
    fun polish() = check("pl")

    @Test
    fun indonesian() = check("id")

    @Test
    fun vietnamese() = check("vi")

    @Test
    fun everyLanguageHasATableAndAnEvalSet() {
        val languages = EngineTestData.tableFiles().map { it.nameWithoutExtension }
        assertThat(languages).containsExactlyElementsIn(EngineTableValidationTest.KNOWN_LANGUAGES)
        languages.forEach {
            assertWithMessage("eval set for $it").that(EngineTestData.evalFile(it).exists()).isTrue()
        }
    }

    private companion object {
        const val MIN_SET = 25
        const val MIN_RECALL = 0.8
    }
}
