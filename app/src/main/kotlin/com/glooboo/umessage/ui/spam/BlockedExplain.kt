package com.glooboo.umessage.ui.spam

import com.glooboo.umessage.helpers.OtpCodeExtractor
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FilterDecision

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

/** What the filter would do with a sample message, for the "Test a message" sheet (ID-52). */
enum class Verdict {
    HELD_BY_RULE,
    HELD_UNKNOWN_WITH_LINK,
    HELD_UNKNOWN,
    ALLOWED_BY_RULE,
    ALLOWED_CONTACT,
    ALLOWED_KNOWN_CONVERSATION,
    ALLOWED_CODE,
    ALLOWED_MODE_OFF,
    ALLOWED_UNSURE,
    ALLOWED_OTHER,
    ;

    val held: Boolean get() = this == HELD_BY_RULE || this == HELD_UNKNOWN_WITH_LINK || this == HELD_UNKNOWN

    companion object {
        fun of(decision: FilterDecision, body: String): Verdict = when (decision) {
            is FilterDecision.Block -> when (decision.reason) {
                DecisionReason.BLOCK_RULE -> HELD_BY_RULE
                else -> if (BlockedHints.hasLink(body)) HELD_UNKNOWN_WITH_LINK else HELD_UNKNOWN
            }
            is FilterDecision.Allow -> when (decision.reason) {
                DecisionReason.ALLOW_RULE -> ALLOWED_BY_RULE
                DecisionReason.CONTACT -> ALLOWED_CONTACT
                DecisionReason.KNOWN_CONVERSATION -> ALLOWED_KNOWN_CONVERSATION
                DecisionReason.OTP_EXEMPTION -> ALLOWED_CODE
                DecisionReason.MODE_OFF -> ALLOWED_MODE_OFF
                DecisionReason.CONTACTS_UNAVAILABLE -> ALLOWED_UNSURE
                else -> ALLOWED_OTHER
            }
        }
    }
}
