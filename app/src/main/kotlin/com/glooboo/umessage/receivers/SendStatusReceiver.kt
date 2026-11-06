package com.glooboo.umessage.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.fossify.commons.helpers.ensureBackgroundThread

abstract class SendStatusReceiver : BroadcastReceiver() {
    // Updates the status of the message in the internal database. Runs on a worker thread.
    abstract fun updateAndroidDatabase(context: Context, intent: Intent, receiverResultCode: Int)

    // allows the implementer to update the status of the message in their database. Runs on the same worker
    // thread after updateAndroidDatabase; the broadcast is held open until it returns.
    abstract fun updateAppDatabase(context: Context, intent: Intent, receiverResultCode: Int)

    override fun onReceive(context: Context, intent: Intent) {
        val resultCode = resultCode
        val pending = goAsync()
        try {
            ensureBackgroundThread {
                try {
                    handle(context, intent, resultCode)
                } finally {
                    runCatching { pending.finish() }
                }
            }
        } catch (t: Throwable) {
            runCatching { pending.finish() }
        }
    }

    /**
     * Runs both updates. Runs on a bare worker thread, where an uncaught exception would kill the whole app, so a
     * malformed status broadcast is logged and dropped instead.
     */
    fun handle(context: Context, intent: Intent, resultCode: Int) {
        try {
            updateAndroidDatabase(context, intent, resultCode)
            updateAppDatabase(context, intent, resultCode)
        } catch (t: Throwable) {
            Log.w(TAG, "Ignoring a send status broadcast that could not be handled", t)
        }
    }

    companion object {
        private const val TAG = "SendStatusReceiver"

        const val SMS_SENT_ACTION = "com.glooboo.spamless.receiver.SMS_SENT"
        const val SMS_DELIVERED_ACTION = "com.glooboo.spamless.receiver.SMS_DELIVERED"

        // Platform callback extras documented by SmsManager.sendTextMessage.
        const val EXTRA_ERROR_CODE = "errorCode"
        const val EXTRA_NO_DEFAULT = "noDefault"
        const val EXTRA_SUB_ID = "subId"

        /** How many parts the message was split into; set by SmsSender on every part's sent intent. */
        const val EXTRA_PART_COUNT = "com.glooboo.spamless.extra.PART_COUNT"

        const val NO_ERROR_CODE = -1

        /** The SMS row id in a content://sms/<id> uri, or null when the uri does not end in a number. */
        fun messageIdOf(uri: Uri?): Long? = uri?.lastPathSegment?.toLongOrNull()
    }
}
