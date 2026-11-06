package com.glooboo.umessage.helpers.smartreply.engine

import com.glooboo.umessage.helpers.smartreply.SmartReplyText

/**
 * An [EngineTable] with every phrase already normalised, built once when the tables are loaded. For a compact script
 * (see [SmartReplyText]) the phrases are also stripped of their spaces. Pure JVM.
 */
internal class EngineIndex(val table: EngineTable) {

    val language: String get() = table.language
    val maxWords: Int get() = table.maxWords
    val maxOptionWords: Int get() = table.choice.maxOptionWords

    val script: SmartReplyText.Script = SmartReplyText.Script.named(table.script)

    val starters: List<String> = phrases(table.yesNo.starters)
    val endings: List<String> = phrases(table.yesNo.endings)
    val whenCues: List<String> = phrases(table.whenBlock.cues)
    val whereCues: List<String> = phrases(table.whereBlock.cues)
    val requestCues: List<String> = phrases(table.request.cues)
    val inviteCues: List<String> = phrases(table.invite.cues)
    val formalMarkers: List<String> = phrases(table.register.formal)
    val informalMarkers: List<String> = phrases(table.register.informal)

    /** Separators, longest first, so "or else" is read before "or". */
    val separators: List<String> = phrases(table.choice.separators).sortedByDescending { it.length }

    /** The separators of a spaced script, split into words. */
    val separatorWords: List<List<String>> = separators.map { it.split(' ') }

    /** One-word answers of the reply pools ("no", "nao"): an "option" like that makes "a or no" a yes/no question. */
    val answerWords: Set<String> = listOf(
        table.yesNo.yes, table.yesNo.no, table.yesNo.later, table.invite.yes, table.invite.no, table.invite.later,
    ).flatten().map { phrases(listOf(it)).firstOrNull().orEmpty() }.filter { it.isNotEmpty() && ' ' !in it }.toSet()

    private val cuePhrases: List<String> =
        (starters + endings + whenCues + whereCues + requestCues + inviteCues).filter { it !in separators }

    /** Question words of a spaced script: every word of a starter, ending, when cue or where cue. */
    val stopWords: Set<String> = (starters + endings + whenCues + whereCues).flatMap { it.split(' ') }.toSet() -
        separatorWords.flatten().toSet()

    /** Longer fillers of a spaced script: the invitation and request cues as word lists, longest first. */
    val stopSequences: List<List<String>> =
        (inviteCues + requestCues).filter { it !in separators }.distinct().map { it.split(' ') }
            .sortedByDescending { it.size }

    /** The same for a compact script: the cues themselves, longest first. */
    val stopPhrases: List<String> = cuePhrases.distinct().sortedByDescending { it.length }

    fun isStarter(reading: EngineReading): Boolean = starters.any(reading::startsWith)

    /** An ending ("right", "mi") only counts when at least two words (characters) come before it: not "Oyle mi?". */
    fun hasEnding(reading: EngineReading): Boolean = endings.any {
        val own = if (script.compact) SmartReplyText.length(it) else it.split(' ').size
        reading.endsWith(it) && reading.size - own >= MIN_BEFORE_ENDING
    }

    fun hasSeparator(reading: EngineReading): Boolean = separators.any(reading::has)

    private fun phrases(list: List<String>): List<String> = list
        .map { SmartReplyText.normalize(it).let { n -> if (script.compact) SmartReplyText.compact(n) else n } }
        .filter { it.isNotEmpty() }
        .distinct()

    private companion object {
        const val MIN_BEFORE_ENDING = 2
    }
}
