package com.glooboo.umessage.spam.mms

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.android.mms.MmsConfig
import com.glooboo.umessage.receivers.BlockedMmsDownloadReceiver
import com.klinker.android.send_message.SmsManagerFactory
import java.io.File
import java.util.UUID

/**
 * Downloads an MMS that the push gate blocked, into uMessage's own storage instead of the system inbox (SE-29).
 *
 * This is the same system download mmslib uses for every MMS (SmsManager.downloadMultimediaMessage into a cache file
 * served by mmslib's MmsFileProvider), but nothing is written to the Telephony provider: no notification-ind row, so
 * the blocked MMS never shows in any chat, in this app or another one. [BlockedMmsDownloadReceiver] keeps the result.
 */
object BlockedMmsDownloader {
    const val EXTRA_BLOCKED_ID = "com.glooboo.spamless.extra.BLOCKED_ID"
    const val EXTRA_FILE = "com.glooboo.spamless.extra.DOWNLOAD_FILE"
    private const val FILE_PREFIX = "blocked-mms-"
    private const val HTTP_PARAMS = "httpParams"

    /** Starts the download. Throws when the system refused to start it; the caller then marks the content missing. */
    fun start(context: Context, blockedId: Long, contentLocation: String, subscriptionId: Int) {
        val name = "$FILE_PREFIX$blockedId-${UUID.randomUUID()}.dat"
        val file = File(context.cacheDir, name)
        val uri = Uri.Builder()
            .scheme("content")
            .authority("${context.packageName}.MmsFileProvider")
            .path(name)
            .build()
        val intent = Intent(context, BlockedMmsDownloadReceiver::class.java)
            .putExtra(EXTRA_BLOCKED_ID, blockedId)
            .putExtra(EXTRA_FILE, file.path)
        // Mutable: the system adds the download result (HTTP status) to it.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        val pending = PendingIntent.getBroadcast(context, (blockedId and Int.MAX_VALUE.toLong()).toInt(), intent, flags)
        val overrides = Bundle().apply {
            MmsConfig.getHttpParams()?.takeIf { it.isNotEmpty() }?.let { putString(HTTP_PARAMS, it) }
        }
        try {
            SmsManagerFactory.createSmsManager(subscriptionId)
                .downloadMultimediaMessage(context, contentLocation, uri, overrides, pending)
        } catch (t: Throwable) {
            file.delete()
            pending.cancel()
            throw t
        }
    }

    /** The download file named in a result intent, only if it is one of ours in the cache directory. */
    fun downloadFile(context: Context, intent: Intent): File? {
        val path = intent.getStringExtra(EXTRA_FILE) ?: return null
        val file = File(path)
        return file.takeIf { it.parentFile?.canonicalPath == context.cacheDir.canonicalPath && it.name.startsWith(FILE_PREFIX) }
    }
}
