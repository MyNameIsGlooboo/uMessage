package com.glooboo.umessage.helpers.smartreply.engine

import com.glooboo.umessage.helpers.smartreply.SmartReply
import com.glooboo.umessage.helpers.smartreply.SmartReplyText
import java.util.Locale

/**
 * The second smart reply layer: reads what a short incoming question asks and answers with fitting chips (see
 * `docs/smart-engine.md`). It knows nothing of the phrase layer; `SmartReplyMatcher` calls it only when that found
 * nothing. A text the app language's table can read is answered by that table alone, so an Italian text never gets
 * a Portuguese chip; otherwise a text that two tables both answer gets nothing. Pure JVM.
 */
class SmartEngine(tables: List<EngineTable>) {

    private val indexes = tables.map(::EngineIndex)

    /** The chips for [body], or null when the engine is not sure. [seed] (the message id) picks the variants. */
    fun reply(body: String, preferredLanguage: String? = null, seed: Long = 0L): SmartReply? {
        val reading = EngineReading.of(body)
        val candidates = if (reading == null) emptyList() else candidatesFor(reading, languageCode(preferredLanguage))
        return candidates.mapNotNull { replyFrom(it, reading!!, seed) }.singleOrNull()
    }

    /** The app language's table when it reads this script, else every table. */
    private fun candidatesFor(reading: EngineReading, preferred: String?): List<EngineIndex> =
        indexes.filter { languageCode(it.language) == preferred && it.script.reads(reading.script) }
            .ifEmpty { indexes }

    private fun replyFrom(index: EngineIndex, reading: EngineReading, seed: Long): SmartReply? {
        val fits = index.script.reads(reading.script) && reading.size <= index.maxWords
        val classification = if (fits) QuestionClassifier.classify(index, reading) else null
        val register = RegisterDetector.detect(index, reading)
        val replies = classification?.let { ReplyComposer.compose(index, it, register, seed) }.orEmpty()
        return if (classification == null || replies.isEmpty()) {
            null
        } else {
            SmartReply(
                intentId = ID_PREFIX + classification.type.name.lowercase(Locale.ROOT),
                language = index.language,
                replies = replies,
                confidence = CONFIDENCE,
                rightToLeft = index.script == SmartReplyText.Script.Arabic,
            )
        }
    }

    /** The bare language of a code as Android or a table writes it: "zh-CN" is "zh", "in" is "id". */
    private fun languageCode(code: String?): String? {
        val language = code?.lowercase(Locale.ROOT)?.substringBefore('-')?.substringBefore('_')
        return LEGACY_CODES[language] ?: language
    }

    private companion object {
        const val ID_PREFIX = "engine_"
        const val CONFIDENCE = 0.8
        val LEGACY_CODES = mapOf("in" to "id", "iw" to "he", "ji" to "yi")
    }
}
