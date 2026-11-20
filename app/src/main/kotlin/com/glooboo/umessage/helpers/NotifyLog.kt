package com.glooboo.umessage.helpers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import org.fossify.commons.extensions.notificationManager
import java.security.MessageDigest

/** What became of one received-message notification. */
enum class NotifyResult {
    POSTED, // the full notification
    POSTED_NO_SHORTCUT, // the full notification, the chat shortcut could not be made
    REPOSTED, // the full notification again after the user's own reply (alert only once): no received text
    FALLBACK, // the plain notification
    FAILED, // neither could be posted
    SKIPPED_MODE, // NotifyMode.NONE
    SKIPPED_READ, // the message is already read
    SKIPPED_STALE, // replay only: the message is gone, moved or too old
    SKIPPED_ACTIVE, // replay only: this chat already shows a notification posted after the message
    SKIPPED_BLOCKED, // replay only: the sender is blocked by a rule now
    CUSTOM_CHANNEL_MISSING, // N8: extra line, the post line follows
}

/** R3: what Android makes of a post: the channel's effective importance ([NO_CHANNEL] = none) and the app switch. */
class NotifyDelivery(val importance: Int, val appEnabled: Boolean) {
    companion object {
        const val NO_CHANNEL = -1
    }
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
    fun record(
        channelId: String?,
        threadId: Long,
        result: NotifyResult,
        cause: Throwable? = null,
        delivery: NotifyDelivery? = null,
    ): NotifyResult {
        // Log.w: release and daily builds strip v, d and i.
        runCatching { Log.w(TAG, line(channelId, threadId, result, cause, delivery)) }
        return result
    }

    /**
     * R3: `notify` does not throw when Android drops or silences a post (app switch off, channel blocked or low, a chat
     * set to "Silent"). The importance of [channelId] as it applies to the chat [conversationId] (API 30+) and the app
     * switch show that. Null when they cannot be read. Never throws.
     */
    fun delivery(context: Context, channelId: String?, conversationId: String? = null): NotifyDelivery? = runCatching {
        val manager = context.notificationManager
        val channel = channelId?.let { channelOf(manager, it, conversationId) }
        NotifyDelivery(channel?.importance ?: NotifyDelivery.NO_CHANNEL, manager.areNotificationsEnabled())
    }.getOrNull()

    /** The line, for tests. */
    internal fun line(
        channelId: String?,
        threadId: Long,
        result: NotifyResult,
        cause: Throwable?,
        delivery: NotifyDelivery? = null,
    ): String {
        val channel = when {
            channelId == null -> "-"
            channelId.isNotEmpty() && channelId.all { it.isDigit() } -> CHAT_CHANNEL
            else -> channelId
        }
        val state = delivery?.let {
            val importance = if (it.importance == NotifyDelivery.NO_CHANNEL) "-" else it.importance.toString()
            " i=$importance on=${if (it.appEnabled) 1 else 0}"
        }.orEmpty()
        val error = cause?.let { " e=${it.javaClass.simpleName}" }.orEmpty()
        return "post ch=$channel t=${threadHash(threadId)} r=${result.name}$state$error"
    }

    /** A chat set to "Silent" by long-press is a conversation channel under [id] (API 30+); else [id] itself. */
    private fun channelOf(manager: NotificationManager, id: String, conversationId: String?): NotificationChannel? {
        if (conversationId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { manager.getNotificationChannel(id, conversationId) }.getOrNull()?.let { return it }
        }
        return manager.getNotificationChannel(id)
    }

    /** First hex characters of SHA-256 of the salted thread id: the same chat gives the same hash, never the id. */
    private fun threadHash(threadId: Long): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$THREAD_SALT$threadId".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(HASH_CHARS)
    }
}
