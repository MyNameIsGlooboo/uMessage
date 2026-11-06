package com.glooboo.umessage.helpers.smartreply

import com.glooboo.umessage.helpers.MessageCategories
import com.glooboo.umessage.spam.TextNormalizer

/**
 * When smart replies may be offered at all. Pure JVM.
 *
 * Only for the newest text of a conversation, when it was received less than six hours ago, from one person the user
 * knows (a saved contact, or a number the user has texted): never in a group, never for a service sender (a name, a
 * short code), never for a text with a link, a sum of money or a number of four digits or more (codes, card numbers).
 */
object SmartReplyPolicy {

    /** The newest text of the open conversation, as far as the policy needs to know it. */
    data class Candidate(
        val address: String,
        val body: String,
        val receivedMillis: Long,
        val isReceived: Boolean,
        val isGroup: Boolean,
        val knownSender: Boolean,
    )

    fun allows(candidate: Candidate, nowMillis: Long): Boolean {
        val age = nowMillis - candidate.receivedMillis
        return candidate.isReceived &&
            !candidate.isGroup &&
            candidate.knownSender &&
            age in -CLOCK_SKEW_MILLIS..MAX_AGE_MILLIS &&
            candidate.address.isNotBlank() &&
            !MessageCategories.isServiceSender(candidate.address) &&
            bodyAllowed(candidate.body)
    }

    /** Whether the words of a text could be answered with a chip, whoever sent it. */
    fun bodyAllowed(body: String): Boolean {
        val folded = TextNormalizer.fold(body)
        return body.isNotBlank() &&
            body.length <= MAX_BODY_CHARS &&
            !LINK.containsMatchIn(folded) &&
            !LONG_NUMBER.containsMatchIn(folded) &&
            !MessageCategories.hasAmount(folded)
    }

    const val MAX_AGE_MILLIS = 6 * 60 * 60 * 1000L
    private const val CLOCK_SKEW_MILLIS = 60 * 1000L
    private const val MAX_BODY_CHARS = 160

    private val LONG_NUMBER = Regex("""\d{4}""")
    private val LINK = Regex(
        """(https?://|www\.)\S+|\b[a-z0-9][a-z0-9-]*\.(ir|com|net|org|shop|store|link|me|app|co|info)\b""",
        RegexOption.IGNORE_CASE,
    )
}
