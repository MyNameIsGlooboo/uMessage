package com.glooboo.umessage.helpers.smartreply.engine

/** What a short question asks. The declaration order is the order [QuestionClassifier] tries the types in. */
internal enum class QuestionType { CHOICE, WHEN, WHERE, INVITE, REQUEST, YES_NO }

/** The verdict of [QuestionClassifier]: [options] are the two pulled options of a CHOICE, else empty. */
internal class Classification(val type: QuestionType, val options: List<String> = emptyList())

/**
 * Rules 3 and 4 of `docs/smart-engine.md`. A text is a question when it carries a question mark or starts with a
 * yes/no starter; the first type, in [QuestionType] order, whose cue matches wins. A separator that cannot be turned
 * into two clean options ends the search with nothing (a choice nobody can answer with a chip), except one with no
 * usable option on a side, which is skipped. Pure JVM.
 */
internal object QuestionClassifier {

    fun classify(index: EngineIndex, reading: EngineReading): Classification? =
        if (reading.hasMark || index.isStarter(reading)) typed(index, reading) else null

    private fun typed(index: EngineIndex, reading: EngineReading): Classification? {
        val choice =
            if (index.hasSeparator(reading)) ChoiceExtractor.extract(index, reading) else ChoiceExtractor.Result.Skip
        return when {
            choice is ChoiceExtractor.Result.Options -> Classification(QuestionType.CHOICE, choice.options)
            choice == ChoiceExtractor.Result.Reject -> null
            else -> byCue(index, reading)
        }
    }

    private fun byCue(index: EngineIndex, reading: EngineReading): Classification? = when {
        index.whenCues.any(reading::has) -> Classification(QuestionType.WHEN)
        index.whereCues.any(reading::has) -> Classification(QuestionType.WHERE)
        index.inviteCues.any(reading::has) -> Classification(QuestionType.INVITE)
        index.requestCues.any(reading::has) -> Classification(QuestionType.REQUEST)
        isYesNo(index, reading) -> Classification(QuestionType.YES_NO)
        else -> null
    }

    /** A starter at the start, or, for a text with a question mark, an ending ("right?") at the end. */
    private fun isYesNo(index: EngineIndex, reading: EngineReading): Boolean =
        index.isStarter(reading) || (reading.hasMark && index.hasEnding(reading))
}
