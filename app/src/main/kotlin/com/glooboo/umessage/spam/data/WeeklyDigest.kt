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

        /** The digest of [blocked] as of [nowMillis]. Messages that arrived earlier than a week ago do not count. */
        fun compute(blocked: List<BlockedMessageEntity>, nowMillis: Long): WeeklyDigest {
            val recent = blocked.filter { it.receivedAt >= nowMillis - WEEK_MS && it.receivedAt <= nowMillis }
            return WeeklyDigest(held = recent.size, important = recent.count(::looksImportant))
        }

        /** Codes (also with a link) and bank alerts without a link: phishing copies the latter (see Blocked tab). */
        private fun looksImportant(message: BlockedMessageEntity): Boolean =
            OtpDetector.looksLikeOtp(message.body) ||
                (
                    !OtpDetector.containsUrl(message.body) &&
                        MessageCategories.classify(message.address, message.body, isContact = false) ==
                        MessageCategory.Transactions
                    )
    }
}
