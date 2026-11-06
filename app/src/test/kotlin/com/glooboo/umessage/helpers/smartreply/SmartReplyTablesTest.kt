package com.glooboo.umessage.helpers.smartreply

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

/** I59: house rules for the shipped tables, so a later edit cannot add an ambiguous or unsafe entry. */
class SmartReplyTablesTest {

    private val tables = SmartReplyTestData.tables()

    @Test
    fun everyFileParsesAndIsNamedAfterItsLanguage() {
        val files = SmartReplyTestData.tableFiles()
        assertThat(files).isNotEmpty()
        for (file in files) {
            val table = SmartReplyTables.parse(file.readText())
            assertWithMessage(file.name).that(table.language).isEqualTo(file.nameWithoutExtension)
            assertWithMessage(file.name).that(KNOWN_LANGUAGES).contains(table.language)
            assertWithMessage(file.name).that(table.intents).isNotEmpty()
        }
    }

    @Test
    fun theFourOriginalLanguagesAndTheNewScriptsShip() {
        val shipped = tables.map { it.language }
        assertThat(shipped).containsAtLeast("en", "fa", "tr", "ar", "zh", "ja", "ko", "th")
        assertThat(shipped).containsNoDuplicates()
    }

    @Test
    fun everyScriptNameIsKnown() {
        val names = SmartReplyText.Script.entries.map { it.tableName }
        for (table in tables) assertWithMessage(table.language).that(names).contains(table.script)
    }

    @Test
    fun noPhraseOrVetoWordIsEmpty() {
        for (table in tables) {
            val all = table.fillers + table.veto +
                table.intents.flatMap { it.exact + it.contains + it.veto }
            for (phrase in all) {
                assertWithMessage("${table.language}: '$phrase'")
                    .that(SmartReplyText.normalize(phrase)).isNotEmpty()
            }
            for (intent in table.intents) {
                val where = "${table.language}/${intent.id}"
                assertWithMessage(where).that(intent.exact.size + intent.contains.size).isGreaterThan(0)
            }
        }
    }

    @Test
    fun everyIntentHasOneToEightShortReplies() {
        for (table in tables) for (intent in table.intents) {
            val where = "${table.language}/${intent.id}"
            assertWithMessage(where).that(intent.replies.size).isIn(1..MAX_VARIANTS)
            val short = intent.replies.all { it.isNotBlank() && it.length <= MAX_REPLY_CHARS }
            assertWithMessage(where).that(short).isTrue()
            assertWithMessage(where).that(intent.replies.distinct()).hasSize(intent.replies.size)
        }
    }

    @Test
    fun intentIdsAreUniquePerTable() {
        for (table in tables) {
            val ids = table.intents.map { it.id }
            assertWithMessage(table.language).that(ids).containsNoDuplicates()
        }
    }

    @Test
    fun noPhraseBelongsToTwoIntents() {
        for (table in tables) {
            val all = SmartReplyIndex(table).intents.flatMap { intent -> intent.exact.map { it to intent.intent.id } }
            val clashes = all.groupBy({ it.first }, { it.second }).filterValues { it.size > 1 }
            assertWithMessage(table.language).that(clashes).isEmpty()
        }
    }

    @Test
    fun noPhraseHoldsItsOwnVetoWord() {
        for (table in tables) {
            val index = SmartReplyIndex(table)
            val compact = index.script.compact
            for (intent in index.intents) {
                val vetoes = index.veto + intent.veto
                for (phrase in intent.exact + intent.contains) {
                    val hit = vetoes.firstOrNull { SmartReplyText.containsPhrase(phrase, it, compact) }
                    assertWithMessage("${table.language}/${intent.intent.id}: $phrase").that(hit).isNull()
                }
            }
        }
    }

    @Test
    fun noPhraseIsEmptyOrTooLong() {
        for (table in tables) for (intent in table.intents) {
            for (phrase in intent.exact + intent.contains) {
                val words = SmartReplyText.normalize(phrase).split(' ').filter { it.isNotEmpty() }
                assertWithMessage("${table.language}/${intent.id}: $phrase").that(words.size).isIn(1..MAX_PHRASE_WORDS)
            }
        }
    }

    @Test
    fun parseFillsDefaultsAndIgnoresUnknownKeys() {
        val table = SmartReplyTables.parse(
            """{"language":"xx","script":"latin","later":1,""" +
                """"intents":[{"id":"a","exact":["hi"],"replies":["Hi!","Yo"]}]}""",
        )
        assertThat(table.minConfidence).isEqualTo(SmartReplyTable.DEFAULT_MIN_CONFIDENCE)
        assertThat(table.maxWords).isEqualTo(SmartReplyTable.DEFAULT_MAX_WORDS)
        assertThat(table.tieBreak).isFalse()
        assertThat(table.intents.single().statement).isFalse()
    }

    private companion object {
        const val MAX_REPLY_CHARS = 60
        const val MAX_VARIANTS = 8
        val KNOWN_LANGUAGES = setOf(
            "en", "fa", "tr", "ar", "zh", "ja", "ko", "th", "bn", "hi", "ru", "uk",
            "fr", "de", "es", "it", "pt", "pl", "id", "vi",
        )
        const val MAX_PHRASE_WORDS = 10
    }
}
