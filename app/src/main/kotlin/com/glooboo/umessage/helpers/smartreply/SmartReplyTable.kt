package com.glooboo.umessage.helpers.smartreply

import kotlinx.serialization.Serializable

/**
 * One language's phrase table, read from `assets/smart_replies/<language>.json`; the format is described in
 * `docs/smart-replies.md`. Phrases are written as people type them: they are normalised by [SmartReplyText] when the
 * table is loaded, so case, apostrophes, punctuation, Persian letter variants and zero-width characters do not matter.
 *
 * [script] is `latin` or `arabic`: a table only reads texts written in its script. When two tables of one script both
 * match (a phrase Persian and Arabic share), the app language decides, then the table with [tieBreak]; no table
 * decides: no chip. [fillers] are greeting and address words that may stand around a phrase ("hey ... bro").
 * A text that holds a [veto] phrase gets no chip at all (sensitive words, money, codes).
 */
@Serializable
data class SmartReplyTable(
    val language: String,
    val script: String,
    val tieBreak: Boolean = false,
    val minConfidence: Double = DEFAULT_MIN_CONFIDENCE,
    val maxWords: Int = DEFAULT_MAX_WORDS,
    val fillers: List<String> = emptyList(),
    val veto: List<String> = emptyList(),
    val intents: List<SmartReplyIntent>,
) {
    companion object {
        const val DEFAULT_MIN_CONFIDENCE = 0.85
        const val DEFAULT_MAX_WORDS = 8
    }
}

/**
 * One kind of message and the replies offered for it (two or three, short, neutral in gender and tone).
 * [exact]: phrases that must make up the whole text (after fillers are dropped). [contains]: phrases that may sit
 * inside a text of at most [SmartReplyTable.maxWords] words; use them only for questions that carry the request.
 * [veto]: words that cancel this intent only. [statement]: the intent never applies to a text with a question mark.
 */
@Serializable
data class SmartReplyIntent(
    val id: String,
    val statement: Boolean = false,
    val exact: List<String> = emptyList(),
    val contains: List<String> = emptyList(),
    val veto: List<String> = emptyList(),
    val replies: List<String>,
)
