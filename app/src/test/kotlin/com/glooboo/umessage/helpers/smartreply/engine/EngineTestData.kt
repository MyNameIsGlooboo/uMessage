package com.glooboo.umessage.helpers.smartreply.engine

import com.glooboo.umessage.helpers.smartreply.SmartReplyText
import java.io.File

/** The shipped engine tables, the labelled eval sets and a tiny fixture table, read from the source tree. */
internal object EngineTestData {

    private fun file(path: String): File = File(path).takeIf { it.exists() } ?: File("app/$path")

    fun tableFiles(): List<File> =
        file("src/main/assets/smart_engine").listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }

    fun tables(): List<EngineTable> = tableFiles().map { EngineTables.parse(it.readText()) }

    fun engine() = SmartEngine(tables())

    fun fixtureText(): String = file("src/test/resources/smart_engine_fixture/en.json").readText()

    fun fixtureTable(): EngineTable = EngineTables.parse(fixtureText())

    fun fixture() = SmartEngine(listOf(fixtureTable()))

    fun evalFile(language: String): File = file("src/test/resources/smart_engine/eval_engine_$language.txt")

    /** One labelled text of `eval_engine_<language>.txt`; [expected] is `TYPE`, `CHOICE:a,b` or NONE. */
    data class Labelled(val expected: String, val text: String) {
        val expectsNothing: Boolean get() = expected == NONE
        val type: String get() = expected.substringBefore(':')
        val intentId: String get() = "engine_" + type.lowercase()

        /** The two options of a CHOICE line, normalised; empty for every other line. */
        val options: List<String>
            get() = if (expected.startsWith("CHOICE:")) {
                expected.substringAfter(':').split(',').map(::normal)
            } else {
                emptyList()
            }
    }

    fun labelled(language: String): List<Labelled> = evalFile(language).readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line -> Labelled(line.substringBefore('|').trim(), line.substringAfter('|')) }

    fun normal(text: String): String = SmartReplyText.normalize(text)

    const val NONE = "NONE"
}
