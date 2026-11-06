package com.glooboo.umessage.helpers.smartreply

import java.io.File

/** The shipped tables and the labelled test sets, read straight from the source tree (tests run in `app/`). */
internal object SmartReplyTestData {

    private fun file(path: String): File =
        File(path).takeIf { it.exists() } ?: File("app/$path")

    fun tableFiles(): List<File> =
        file("src/main/assets/smart_replies").listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }

    fun tables(): List<SmartReplyTable> = tableFiles().map { SmartReplyTables.parse(it.readText()) }

    fun matcher() = SmartReplyMatcher(tables())

    /** One labelled text of `eval_<language>.txt`: [intent] is the expected intent id, or NONE. */
    data class Labelled(val intent: String, val text: String) {
        val expectsNothing: Boolean get() = intent == NONE
    }

    fun evalFile(language: String): File = file("src/test/resources/smart_replies/eval_$language.txt")

    fun labelled(language: String): List<Labelled> = evalFile(language).readLines()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line -> Labelled(line.substringBefore('|'), line.substringAfter('|')) }

    const val NONE = "NONE"
}
