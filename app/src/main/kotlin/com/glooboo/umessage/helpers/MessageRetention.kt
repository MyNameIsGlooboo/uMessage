package com.glooboo.umessage.helpers

import android.provider.Telephony
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.spam.engine.OtpDetector

/**
 * I02 and I45: which cached messages the automatic cleanup may move to the recycle bin. Pure: the caller hands in the
 * candidates and says which of them are starred or kept (note, reminder). A message is never touched when it is
 * unread, starred, noted, reminded, scheduled or not a plain received or sent one (drafts, outbox, failed). Moving
 * to the bin is the only thing the cleanup does, so
 * everything it picks can be brought back.
 */
object MessageRetention {

    /** At most this many messages move in one batch. */
    const val BATCH = 500

    /** A longer text is never taken for a verification code, whatever it says. */
    private const val MAX_CODE_LENGTH = 300

    const val DAY_MS = 24L * 60 * 60 * 1000
    private const val SECOND_MS = 1000L

    /** Messages dated before this (in seconds, like [Message.date]) are old for a keep-time of [days]. */
    fun cutoffSeconds(nowMillis: Long, days: Int): Int =
        ((nowMillis - days * DAY_MS) / SECOND_MS).toSecondsInt()

    /** Received or sent, not a draft, an outbox message or a failed one. */
    fun isPlainMessage(message: Message): Boolean =
        message.type == Telephony.Sms.MESSAGE_TYPE_INBOX || message.type == Telephony.Sms.MESSAGE_TYPE_SENT

    /** A short received text that the strict code detector flags and that carries no link. */
    fun isCode(message: Message): Boolean =
        message.type == Telephony.Sms.MESSAGE_TYPE_INBOX &&
            !message.isMMS &&
            message.body.length <= MAX_CODE_LENGTH &&
            OtpDetector.looksLikeOtp(message.body) &&
            !OtpDetector.containsUrl(message.body)

    /** Starred, noted and reminded messages are kept (F6): they hold something the user wrote or asked for. */
    private fun safeToMove(message: Message, isKept: (Message) -> Boolean, isStarred: (Message) -> Boolean): Boolean =
        message.read && !message.isScheduled && isPlainMessage(message) && !isStarred(message) && !isKept(message)

    /** The read, unstarred verification codes of [candidates] dated before [cutoffSeconds]. */
    fun selectCodes(
        candidates: List<Message>,
        cutoffSeconds: Int,
        isKept: (Message) -> Boolean = { false },
        isStarred: (Message) -> Boolean,
    ): List<Message> =
        candidates.filter { it.date < cutoffSeconds && safeToMove(it, isKept, isStarred) && isCode(it) }

    /**
     * The read, unstarred messages of [candidates] dated before [cutoffSeconds] that are not in a pinned chat
     * ([pinnedThreads]).
     */
    fun selectOld(
        candidates: List<Message>,
        cutoffSeconds: Int,
        pinnedThreads: Set<Long>,
        isKept: (Message) -> Boolean = { false },
        isStarred: (Message) -> Boolean,
    ): List<Message> =
        candidates.filter {
            it.date < cutoffSeconds && it.threadId !in pinnedThreads && safeToMove(it, isKept, isStarred)
        }
}
