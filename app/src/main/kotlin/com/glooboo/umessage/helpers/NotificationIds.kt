package com.glooboo.umessage.helpers

import android.content.Context
import org.fossify.commons.extensions.notificationManager

/**
 * Notification id spaces. A chat's message notification uses the thread id itself ([Long.hashCode], small numbers), so
 * every other kind of notification that belongs to a chat or a reminder gets its own salted id: they can no longer
 * replace, or cancel, each other (a reminder with id 7 used to share the id of the chat with thread id 7).
 */
object NotificationIds {
    private const val REMINDER_SALT = 0x20000000
    private const val FAILED_SEND_SALT = 0x40000000
    private const val SENT_LATE_SALT = 0x10000000
    private const val MMS_DOWNLOAD_SALT = 0x08000000

    /** The notification of reminder [reminderId]. */
    fun reminder(reminderId: Long): Int = reminderId.hashCode() xor REMINDER_SALT

    /** "Message not sent" for a chat: one per chat, so repeated failures replace each other. */
    fun failedSend(threadId: Long): Int = threadId.hashCode() xor FAILED_SEND_SALT

    /** True when [id] is one of [failedSend]'s ids (thread ids stay far below the salt, so the bit tells). */
    fun isFailedSend(id: Int): Boolean = (id xor FAILED_SEND_SALT) in 0 until SENT_LATE_SALT

    /** "Sent late" for a scheduled message of a chat. */
    fun sentLate(threadId: Long): Int = threadId.hashCode() xor SENT_LATE_SALT

    /** "Picture message not downloaded" for a chat. */
    fun mmsDownloadFailed(threadId: Long): Int = threadId.hashCode() xor MMS_DOWNLOAD_SALT
}

/** Whether any "Message not sent" notification is showing, so a sent report only looks up its chat when it matters. */
fun Context.hasFailedSendNotification(): Boolean =
    runCatching { notificationManager.activeNotifications.any { NotificationIds.isFailedSend(it.id) } }
        .getOrDefault(false)

/** Removes the "Message not sent" notification of [threadId], once the chat is open or the message went out. */
fun Context.cancelFailedSendNotification(threadId: Long) {
    runCatching { notificationManager.cancel(NotificationIds.failedSend(threadId)) }
}
