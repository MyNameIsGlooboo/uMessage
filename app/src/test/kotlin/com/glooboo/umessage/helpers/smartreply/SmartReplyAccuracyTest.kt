package com.glooboo.umessage.helpers.smartreply

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/**
 * I59 precision gate, per language. The labelled sets (`src/test/resources/smart_replies/eval_<language>.txt`) are
 * invented texts; a table edit that makes a chip appear where none belongs, or a wrong chip where another belongs,
 * fails here. A wrong chip is worse than none, so the gate is strict where it matters: no false show, no wrong
 * intent; coverage only has to stay above [MIN_RECALL].
 */
class SmartReplyAccuracyTest {

    private val matcher = SmartReplyTestData.matcher()

    private fun check(language: String) {
        val set = SmartReplyTestData.labelled(language)
        val positives = set.filterNot { it.expectsNothing }
        val negatives = set.filter { it.expectsNothing }
        assertWithMessage("$language positives").that(positives.size).isAtLeast(MIN_SET)
        assertWithMessage("$language negatives").that(negatives.size).isAtLeast(MIN_SET)

        val falseShows = negatives.filter { matcher.match(it.text, language) != null }
        assertWithMessage("$language: chips shown where none belong").that(falseShows.map { it.text }).isEmpty()

        val shown = positives.map { it to matcher.match(it.text, language) }
        val wrong = shown.filter { (labelled, reply) -> reply != null && reply.intentId != labelled.intent }
        assertWithMessage("$language: wrong intent").that(wrong.map { it.first.text }).isEmpty()

        val hits = shown.count { (labelled, reply) -> reply?.intentId == labelled.intent }
        val recall = hits.toDouble() / positives.size
        assertWithMessage("$language recall $hits of ${positives.size}").that(recall).isAtLeast(MIN_RECALL)
    }

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
    fun everyTableHasItsLabelledSet() {
        val languages = SmartReplyTestData.tables().map { it.language }
        assertThat(languages).isNotEmpty()
        languages.forEach {
            assertWithMessage("labelled set for $it").that(SmartReplyTestData.evalFile(it).exists()).isTrue()
        }
    }

    private companion object {
        const val MIN_SET = 30
        const val MIN_RECALL = 0.8
    }
}
