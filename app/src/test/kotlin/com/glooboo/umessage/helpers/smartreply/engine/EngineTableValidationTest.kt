package com.glooboo.umessage.helpers.smartreply.engine

import com.glooboo.umessage.helpers.smartreply.SmartReplyText
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.util.Locale

/** House rules for the shipped files of `assets/smart_engine`, so a later edit cannot add a broken entry. */
class EngineTableValidationTest {

    private val files = EngineTestData.tableFiles()

    private fun tables() = files.map { it.nameWithoutExtension to EngineTables.parse(it.readText()) }

    private fun pools(t: EngineTable): Map<String, List<String>> = mapOf(
        "yes" to t.yesNo.yes, "no" to t.yesNo.no, "later" to t.yesNo.later,
        "when" to t.whenBlock.replies, "where" to t.whereBlock.replies, "request" to t.request.replies,
        "invite.yes" to t.invite.yes, "invite.no" to t.invite.no, "invite.later" to t.invite.later,
    )

    private fun cues(t: EngineTable): Map<String, List<String>> = mapOf(
        "starters" to t.yesNo.starters, "endings" to t.yesNo.endings, "when" to t.whenBlock.cues,
        "where" to t.whereBlock.cues, "separators" to t.choice.separators, "request" to t.request.cues,
        "invite" to t.invite.cues, "formal markers" to t.register.formal, "informal markers" to t.register.informal,
    )

    @Test
    fun everyFileParsesAndIsNamedAfterItsLanguage() {
        assertThat(files).isNotEmpty()
        for ((name, table) in tables()) {
            assertWithMessage(name).that(table.language).isEqualTo(name)
            assertWithMessage(name).that(KNOWN_LANGUAGES).contains(table.language)
        }
    }

    @Test
    fun everyScriptNameIsKnown() {
        val names = SmartReplyText.Script.entries.map { it.tableName }
        for ((name, table) in tables()) assertWithMessage(name).that(names).contains(table.script)
    }

    @Test
    fun everyReplyPoolHasFourToEightShortEntries() {
        for ((name, table) in tables()) {
            for ((pool, entries) in pools(table) + ("either" to table.choice.either)) {
                val min = if (pool == "either") MIN_EITHER else MIN_POOL
                assertWithMessage("$name $pool size").that(entries.size).isIn(min..MAX_POOL)
                for (entry in entries) {
                    val fine = entry.isNotBlank() && entry.length <= MAX_CHARS
                    assertWithMessage("$name $pool '$entry'").that(fine).isTrue()
                }
            }
        }
    }

    @Test
    fun noCueIsEmptyAndEveryCueIsLowerCase() {
        for ((name, table) in tables()) {
            for ((block, list) in cues(table)) {
                for (cue in list) {
                    assertWithMessage("$name $block '$cue'").that(SmartReplyText.normalize(cue)).isNotEmpty()
                    assertWithMessage("$name $block '$cue' lower case").that(cue).isEqualTo(cue.lowercase(Locale.ROOT))
                }
            }
        }
    }

    @Test
    fun everyTypeCanBeReached() {
        for ((name, table) in tables()) {
            assertWithMessage("$name starters").that(table.yesNo.starters).isNotEmpty()
            assertWithMessage("$name when cues").that(table.whenBlock.cues).isNotEmpty()
            assertWithMessage("$name where cues").that(table.whereBlock.cues).isNotEmpty()
            assertWithMessage("$name separators").that(table.choice.separators).isNotEmpty()
            assertWithMessage("$name request cues").that(table.request.cues).isNotEmpty()
            assertWithMessage("$name invite cues").that(table.invite.cues).isNotEmpty()
        }
    }

    @Test
    fun formalPoolsAreKnownAndShort() {
        val known = pools(EngineTable("x", "latin")).keys + "either"
        for ((name, table) in tables()) {
            for ((pool, entries) in table.formal) {
                assertWithMessage("$name formal $pool").that(known).contains(pool)
                assertWithMessage("$name formal $pool empty").that(entries).isNotEmpty()
                assertWithMessage("$name formal $pool length").that(entries.all { it.length <= MAX_CHARS }).isTrue()
            }
        }
    }

    @Test
    fun maxWordsAndOptionWordsAreSane() {
        for ((name, table) in tables()) {
            assertWithMessage("$name maxWords").that(table.maxWords).isIn(MIN_WORDS..MAX_WORDS)
            assertWithMessage("$name maxOptionWords").that(table.choice.maxOptionWords).isIn(1..MAX_OPTION_WORDS)
        }
    }

    companion object {
        val KNOWN_LANGUAGES = listOf(
            "en", "fa", "ar", "tr", "zh", "ja", "ko", "th", "bn", "hi",
            "ru", "uk", "fr", "de", "es", "it", "pt", "pl", "id", "vi",
        )
        private const val MIN_POOL = 4
        private const val MIN_EITHER = 3
        private const val MAX_POOL = 8
        private const val MAX_CHARS = 40
        private const val MIN_WORDS = 4
        private const val MAX_WORDS = 40
        private const val MAX_OPTION_WORDS = 12
    }
}
