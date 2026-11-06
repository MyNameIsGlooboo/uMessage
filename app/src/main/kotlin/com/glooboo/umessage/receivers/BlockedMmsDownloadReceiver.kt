package com.glooboo.umessage.receivers

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.glooboo.umessage.spam.engine.SpamEngine
import com.glooboo.umessage.spam.mms.BlockedMmsContent
import com.glooboo.umessage.spam.mms.BlockedMmsDownloader
import com.glooboo.umessage.spam.mms.BlockedMmsStore
import org.fossify.commons.helpers.ensureBackgroundThread

/**
 * Result of a [BlockedMmsDownloader] download (not exported; only the system's PendingIntent reaches it). Keeps the
 * downloaded MMS in app-private storage and records it on the blocked row, or records that it could not be
 * downloaded, so the Blocked screen and "Not spam" can tell the user the truth.
 */
class BlockedMmsDownloadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext
        val result = resultCode
        try {
            ensureBackgroundThread {
                try {
                    handle(appContext, intent, result)
                } finally {
                    runCatching { pending.finish() }
                }
            }
        } catch (t: Throwable) {
            runCatching { pending.finish() }
        }
    }

    internal companion object {
        private const val TAG = "uMessageReceive"

        /** Never throws. The temporary download file is always deleted. */
        fun handle(context: Context, intent: Intent, resultCode: Int) {
            val blockedId = intent.getLongExtra(BlockedMmsDownloader.EXTRA_BLOCKED_ID, -1L)
            val file = BlockedMmsDownloader.downloadFile(context, intent)
            try {
                if (blockedId <= 0) return
                val pdu = if (resultCode == Activity.RESULT_OK) {
                    runCatching { file?.takeIf { it.isFile }?.readBytes() }.getOrNull()
                } else {
                    null
                }
                val content = BlockedMmsContent.parse(pdu)
                val name = if (pdu != null && content != null) BlockedMmsStore.save(context, pdu, content) else null
                if (name == null) Log.i(TAG, "Blocked MMS could not be downloaded (result $resultCode)")
                if (!SpamEngine.attachDownloadedMedia(context, blockedId, name, content?.text)) {
                    // Restored or deleted while it was downloading, or the database failed: keep nothing.
                    name?.let { BlockedMmsStore.delete(context, listOf(it)) }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Blocked MMS download result could not be kept", t)
            } finally {
                runCatching { file?.delete() }
            }
        }
    }
}
