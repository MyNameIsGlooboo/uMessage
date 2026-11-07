package com.glooboo.umessage.receivers

import android.content.BroadcastReceiver
import android.util.Log
import org.fossify.commons.helpers.ensureBackgroundThread

/**
 * PF-B6: runs [work] on a worker thread inside the broadcast's goAsync window and always finishes the broadcast.
 * A bare worker thread kills the whole app on an uncaught exception, and the actions of old notifications (Delete,
 * Mark as read, Reply, a due reminder) can throw, for example a SecurityException from the SMS provider once this
 * is no longer the default SMS app. Such a failure is logged without any content and dropped.
 */
@Suppress("TooGenericExceptionCaught")
internal fun BroadcastReceiver.runInBackground(tag: String, work: () -> Unit) {
    val pending = goAsync()
    try {
        ensureBackgroundThread {
            try {
                work()
            } catch (t: Throwable) {
                Log.w(tag, "Notification action could not be completed", t)
            } finally {
                runCatching { pending.finish() }
            }
        }
    } catch (_: Throwable) {
        runCatching { pending.finish() }
    }
}
