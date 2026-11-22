package com.glooboo.umessage.ui.spam

import com.glooboo.umessage.helpers.OtpCodeExtractor
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason

/** Why a message was held, in the words the open message shows (ID-16). */
enum class HeldWhy {
    /** Unknown sender, and the text has a link: Smart mode never lets a code with a link through. */
    UNKNOWN_WITH_LINK,

    /** Unknown sender, no link. */
    UNKNOWN,

    /** One of the user's block rules (its reason chip names the rule, so no sentence repeats it). */
    RULE,
}

data class HeldExplanation(val why: HeldWhy, val code: String?)

object BlockedExplain {

    /**
     * The explanation of each held message with text, whatever held it, so every message of a group gets its own
     * Copy code and Release once (bug 13). Null for a message without text and for reasons that are neither an
     * unknown sender nor a rule. [HeldExplanation.code] is the one-time code in the text, when there is one.
     */
    fun of(message: BlockedMessage): HeldExplanation? {
        if (!message.hasText) return null
        val why = when {
            message.reason == DecisionReason.BLOCK_RULE -> HeldWhy.RULE
            message.reason != DecisionReason.UNKNOWN_SENDER && message.reason != DecisionReason.FOREIGN_NUMBER ->
                return null
            BlockedHints.hasLink(message.body) -> HeldWhy.UNKNOWN_WITH_LINK
            else -> HeldWhy.UNKNOWN
        }
        val code = runCatching { OtpCodeExtractor.extract(message.body) }.getOrNull()
        return HeldExplanation(why, code)
    }
}
