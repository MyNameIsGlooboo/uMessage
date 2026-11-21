package com.glooboo.umessage.spam.data

import com.glooboo.umessage.helpers.MessageCategories
import com.glooboo.umessage.helpers.MessageCategory
import com.glooboo.umessage.spam.engine.OtpDetector

/**
 * I08: the numbers of the weekly summary. [held] is how many messages wait in Blocked that arrived in the last week,
 * [important] how many of those look like a verification code or a bank alert, the kinds of text a person would not
 * want to miss. Counts only: the summary never carries a message text or a number. Pure JVM.
 */
data class WeeklyDigest(val held: Int, val important: Int) {

    companion object {
        const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

        /** H-15: how much earlier than its chat's spam_since a text may be dated and still count (an MMS download). */
        const val ARRIVAL_GRACE_MS = 10L * 60 * 1000
        private const val SECOND_MS = 1000L

        /** The digest of [blocked] as of [nowMillis]. Messages that arrived earlier than a week ago do not count. */
        fun compute(blocked: List<BlockedMessageEntity>, nowMillis: Long): WeeklyDigest =
            computeTexts(blocked.map { SpamText(it.address, it.body, it.receivedAt) }, nowMillis)

        /**
         * Provider truth (section 5): the digest of the received [texts] of the chats in Spam, as of [nowMillis]. Only
         * texts of the last week count, and of those only the ones that arrived while their chat was in Spam
         * ([SpamText.since]); the wording stays as it is for now (U18). H-15: a stored text is dated in whole seconds
         * and an MMS by its own date, before its download put the chat in Spam, so the comparison is in seconds and
         * allows [ARRIVAL_GRACE_MS]: the text that put its chat in Spam counts.
         */
        fun computeTexts(texts: List<SpamText>, nowMillis: Long): WeeklyDigest {
            val recent = texts.filter {
                it.receivedAt >= nowMillis - WEEK_MS && it.receivedAt <= nowMillis &&
                    it.receivedAt / SECOND_MS >= (it.since - ARRIVAL_GRACE_MS) / SECOND_MS
            }
            return WeeklyDigest(held = recent.size, important = recent.count(::looksImportant))
        }

        /** Codes (also with a link) and bank alerts without a link: phishing copies the latter (see Blocked tab). */
        private fun looksImportant(text: SpamText): Boolean =
            OtpDetector.looksLikeOtp(text.body) ||
                (
                    !OtpDetector.containsUrl(text.body) &&
                        MessageCategories.classify(text.address, text.body, isContact = false) ==
                        MessageCategory.Transactions
                    )
    }

    /**
     * One received text the digest counts: who sent it, its text and when it arrived (milliseconds). [since] is when
     * its chat entered Spam; a text from before does not count. Held only while the digest is counted.
     */
    data class SpamText(val address: String, val body: String, val receivedAt: Long, val since: Long = 0L)
}
