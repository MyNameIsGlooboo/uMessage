package com.glooboo.umessage.helpers.smartreply.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One language's question-understanding table, read from `assets/smart_engine/<language>.json`; the format is
 * described in `docs/smart-engine.md`. Every block is optional: a type whose block is missing is never offered.
 * Phrases are normalised by [EngineIndex] when the table is loaded, so case, accents and look-alike letters do not
 * matter. [formal] maps a pool name (`yes`, `no`, `later`, `when`, `where`, `request`, `either`; `invite.yes` and so
 * on for the invitation pools) to the pool used when the sender writes formally.
 */
@Serializable
data class EngineTable(
    val language: String,
    val script: String,
    val maxWords: Int = DEFAULT_MAX_WORDS,
    val yesNo: YesNoBlock = YesNoBlock(),
    @SerialName("when") val whenBlock: CueReplies = CueReplies(),
    @SerialName("where") val whereBlock: CueReplies = CueReplies(),
    val choice: ChoiceBlock = ChoiceBlock(),
    val request: CueReplies = CueReplies(),
    val invite: InviteBlock = InviteBlock(),
    val register: RegisterBlock = RegisterBlock(),
    val formal: Map<String, List<String>> = emptyMap(),
) {
    companion object {
        const val DEFAULT_MAX_WORDS = 14
    }
}

@Serializable
data class YesNoBlock(
    val starters: List<String> = emptyList(),
    val endings: List<String> = emptyList(),
    val yes: List<String> = emptyList(),
    val no: List<String> = emptyList(),
    val later: List<String> = emptyList(),
)

@Serializable
data class CueReplies(
    val cues: List<String> = emptyList(),
    val replies: List<String> = emptyList(),
)

@Serializable
data class ChoiceBlock(
    val separators: List<String> = emptyList(),
    val maxOptionWords: Int = DEFAULT_MAX_OPTION_WORDS,
    val either: List<String> = emptyList(),
) {
    companion object {
        const val DEFAULT_MAX_OPTION_WORDS = 3
    }
}

@Serializable
data class InviteBlock(
    val cues: List<String> = emptyList(),
    val yes: List<String> = emptyList(),
    val no: List<String> = emptyList(),
    val later: List<String> = emptyList(),
)

@Serializable
data class RegisterBlock(
    val formal: List<String> = emptyList(),
    val informal: List<String> = emptyList(),
)
