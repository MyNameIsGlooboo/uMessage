package com.glooboo.umessage.helpers

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.glooboo.umessage.R
import com.glooboo.umessage.activities.ThreadActivity
import org.fossify.commons.extensions.notificationManager

/**
 * N5: the notification that goes out when the full one could not be built. Sender, text and "open chat", nothing
 * else.
 */
internal class PlainMessageNotification(
    private val context: Context,
    /** Creates the normal message channel (id, name) when it is not there yet. */
    private val ensureChannel: (String, String) -> Unit,
) {
    class Request(
        val threadId: Long,
        val address: String,
        val sender: String?,
        val body: String,
        val channelId: String?,
        val alertOnlyOnce: Boolean = false,
    )

    /**
     * Posts with id `threadId.hashCode()`. Returns FALLBACK, or FAILED when this throws too or no channel can be had.
     * Logs one line. Never throws.
     */
    fun post(request: Request, cause: Throwable): NotifyResult {
        val threadId = request.threadId
        // R9: Android drops a post to a channel that does not exist, without an error. FAILED keeps the marker.
        val channel = channelFor(request.channelId)
            ?: return NotifyLog.record(null, threadId, NotifyResult.FAILED, cause)
        val result = runCatching {
            notifyFault?.invoke(NotifyStage.PLAIN)
            context.notificationManager.notify(threadId.hashCode(), build(request, channel))
        }
        val delivery = NotifyLog.delivery(context, channel, threadId.toString())
        return result.fold(
            onSuccess = { NotifyLog.record(channel, threadId, NotifyResult.FALLBACK, cause, delivery) },
            onFailure = { NotifyLog.record(channel, threadId, NotifyResult.FAILED, it, delivery) },
        )
    }

    /**
     * The given channel while Android still has it, otherwise the normal one (created here when it is missing). Null
     * when the normal one is still missing after that.
     */
    private fun channelFor(requested: String?): String? {
        val existing = runCatching {
            requested?.takeIf { context.notificationManager.getNotificationChannel(it) != null }
        }.getOrNull()
        if (existing != null) return existing
        runCatching { ensureChannel(NOTIFICATION_CHANNEL_ID, context.getString(R.string.channel_received_sms)) }
        // A read that fails says nothing about the channel: post anyway.
        val exists = runCatching {
            context.notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) != null
        }.getOrDefault(true)
        return NOTIFICATION_CHANNEL_ID.takeIf { exists }
    }

    private fun build(request: Request, channel: String): Notification {
        val threadId = request.threadId
        // Unknown counts as hidden, so a hidden chat can never leak through this form.
        val hidden = runCatching { HiddenChats.get(context).isMarked(threadId) }.getOrDefault(true)
        val appName = context.getString(R.string.app_name)
        val title = when {
            hidden -> appName
            !request.sender.isNullOrBlank() -> request.sender
            request.address.isNotBlank() -> request.address
            else -> appName
        }
        // An MMS without text has a blank body: "New message" instead of an empty line.
        val text = when {
            hidden || request.body.isBlank() -> context.getString(R.string.new_message)
            else -> shownText(request.body)
        }
        val open = PendingIntent.getActivity(
            context,
            threadId.hashCode(),
            Intent(context, ThreadActivity::class.java).putExtra(THREAD_ID, threadId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // minSdk 26: the channel decides sound and importance.
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_messenger)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setAutoCancel(true)
            .setOnlyAlertOnce(request.alertOnlyOnce)
            .build()
    }

    /** The body, with a code masked when codes are hidden on the lock screen (as the full notification does). */
    private fun shownText(body: String): String = runCatching {
        val hideCode = OtpCodeExtractor.extract(body) != null && context.w3aPrefs.hideCodesOnLockScreen
        if (hideCode) CodeMasker.mask(body) else body
    }.getOrElse { context.getString(R.string.new_message) }
}
