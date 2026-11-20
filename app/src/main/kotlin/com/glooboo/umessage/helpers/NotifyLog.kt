package com.glooboo.umessage.helpers

import android.util.Log
import java.security.MessageDigest

/** What became of one received-message notification. */
enum class NotifyResult {
    POSTED, // the full notification
    POSTED_NO_SHORTCUT, // the full notification, the chat shortcut could not be made
    FALLBACK, // the plain notification
    FAILED, // neither could be posted
    SKIPPED_MODE, // NotifyMode.NONE
    SKIPPED_READ, // the message is already read
    SKIPPED_STALE, // replay only: the message is gone, moved or too old
    SKIPPED_ACTIVE, // replay only: this chat already shows a notification posted after the message
    SKIPPED_BLOCKED, // replay only: the sender is blocked by a rule now
    CUSTOM_CHANNEL_MISSING, // N8: extra line, the post line follows
}

/**
 * N5: one line per received-message notification under [TAG], so the phone's log shows whether it went out. The line
 * holds a channel id, a hash of the thread and the result: never a sender, a number, a name or message text.
 */
object NotifyLog {
    const val TAG = "uMessageNotify"

    /** Per-chat channels use the thread id as their id; it is written as this word instead. */
    private const val CHAT_CHANNEL = "chat"
    private const val THREAD_SALT = "uMessage-thread-"
    private const val HASH_CHARS = 8

    /** Writes the line and returns [result], so a caller can write `return NotifyLog.record(...)`. Never throws. */
    fun record(channelId: String?, threadId: Long, result: NotifyResult, cause: Throwable? = null): NotifyResult {
        // Log.w: release and daily builds strip v, d and i.
        runCatching { Log.w(TAG, line(channelId, threadId, result, cause)) }
        return result
    }

    /** The line, for tests. */
    internal fun line(channelId: String?, threadId: Long, result: NotifyResult, cause: Throwable?): String {
        val channel = when {
            channelId == null -> "-"
            channelId.isNotEmpty() && channelId.all { it.isDigit() } -> CHAT_CHANNEL
            else -> channelId
        }
        val error = cause?.let { " e=${it.javaClass.simpleName}" }.orEmpty()
        return "post ch=$channel t=${threadHash(threadId)} r=${result.name}$error"
    }

    /** First hex characters of SHA-256 of the salted thread id: the same chat gives the same hash, never the id. */
    private fun threadHash(threadId: Long): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$THREAD_SALT$threadId".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(HASH_CHARS)
    }
}
