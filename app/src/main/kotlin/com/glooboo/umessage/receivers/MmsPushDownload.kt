package com.glooboo.umessage.receivers

import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.preference.PreferenceManager
import android.provider.Telephony
import android.util.Log
import com.android.mms.MmsConfig
import com.android.mms.transaction.NotificationTransaction
import com.android.mms.transaction.PushReceiver
import com.glooboo.umessage.helpers.showMmsDownloadFailedNotification
import com.glooboo.umessage.messaging.MmsDownloadRules
import com.glooboo.umessage.messaging.MmsDownloadTracker
import com.glooboo.umessage.spam.mms.AndroidMmsSpamStore
import com.glooboo.umessage.spam.mms.IncomingMms
import com.glooboo.umessage.spam.mms.MmsPushGate
import com.glooboo.umessage.spam.mms.SpamNotice
import com.google.android.mms.pdu_alt.NotificationInd
import com.google.android.mms.pdu_alt.PduParser
import com.google.android.mms.pdu_alt.PduPersister
import com.klinker.android.send_message.MmsReceivedReceiver
import com.klinker.android.send_message.SmsManagerFactory
import com.klinker.android.send_message.Transaction
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The push (notification-ind) of an MMS that is let through: saves it in the system provider and starts the download
 * (H8). mmslib's PushReceiver does the same, but its DownloadManager gives the system an implicit PendingIntent for a
 * receiver it registers at run time, so the result is lost when the process is frozen or killed during a 10 to 60 s
 * carrier download. Here the PendingIntent names [MmsReceiver] (declared in the manifest, not exported), so the
 * system starts the app again for the result and the normal save, filter and notification path runs.
 *
 * Only the notification-ind is handled here; every other push (delivery and read reports, invalid data) is left to
 * the library, see [handle].
 *
 * Provider truth (6.2): a notice whose chat is in Spam ([SpamNotice]) is saved the same way, read and seen. Within the
 * limits it downloads here too; otherwise it gets its chat in Spam at once and stays a "Tap to download" row.
 */
@Suppress("TooGenericExceptionCaught") // Fail open: on any error the library or "Tap to download" takes over.
internal class MmsPushDownload(
    private val isDuplicate: (Context, NotificationInd) -> Boolean = { c, i -> isDuplicateNotification(c, i) },
    private val persist: (Context, NotificationInd, Int) -> Uri? = { c, i, sub -> persistNotification(c, i, sub) },
    /** Content location and transaction id of the saved notification-ind row. */
    private val lookup: (Context, Uri) -> Pair<String, String> = { c, uri ->
        PushReceiver.getContentLocation(c, uri) to PushReceiver.getTransactionId(c, uri)
    },
    private val start: (Context, String, String, Uri, Int) -> Unit = { c, loc, id, uri, sub ->
        startDownload(c, loc, id, uri, sub)
    },
    /**
     * Runs when the download could not be started: tells the user and gives the saved row a thread (in Spam for a
     * [SpamNotice]).
     */
    private val onStartFailed: (Context, Uri, String, SpamNotice?) -> Unit = { c, uri, from, spam ->
        reportStartFailed(c, uri, from, spam)
    },
    private val tracker: MmsDownloadTracker = MmsDownloadTracker.shared,
    /** Provider truth: the store and Spam table side of a [SpamNotice]. */
    private val incoming: (Context) -> IncomingMms = { IncomingMms(AndroidMmsSpamStore(it)) },
) {

    private sealed interface Saved {
        data object Duplicate : Saved
        data object Failed : Saved
        data class Row(val uri: Uri) : Saved
    }

    /**
     * True when this push was a notification-ind and is dealt with (saved and the download started, or a duplicate of
     * one already handled). False when the library should handle it, which is also the answer for anything that goes
     * wrong before the notification-ind is saved: a message is never dropped here. After it is saved, a failed
     * download start leaves the usual "Tap to download" row, notifies, and still returns true. [spam]: provider truth,
     * the push gate put the sender's chat in Spam (null: Messages, or the flag is off).
     */
    fun handle(context: Context, intent: Intent, spam: SpamNotice? = null): Boolean {
        val indication = readPush(context, intent) ?: return false
        val subscriptionId = intent.receivingSubscriptionId()
        return when (val saved = save(context, indication, subscriptionId)) {
            Saved.Duplicate -> true
            Saved.Failed -> false
            is Saved.Row -> {
                when {
                    spam == null -> download(context, saved.uri, indication, subscriptionId, null)
                    spam.downloads -> {
                        incoming(context).quietNotice(saved.uri)
                        download(context, saved.uri, indication, subscriptionId, spam)
                    }
                    else -> revealInSpam(context, saved.uri, spam)
                }
                true
            }
        }
    }

    /** A Spam notice that is not downloaded now: its chat in Spam, read and seen, as a "Tap to download" row. */
    private fun revealInSpam(context: Context, saved: Uri, spam: SpamNotice) {
        // Without its Spam row the notice shows in Messages, unread (fail open); see IncomingMms.revealNotice.
        runCatching { incoming(context).revealNotice(saved, spam.sender, spam.cause) }
            .onSuccess { Log.i(MmsReceiver.TAG, "Spam MMS notice kept; not downloaded now") }
            .onFailure { Log.w(MmsReceiver.TAG, "Spam MMS notice could not be given its chat", it) }
    }

    private fun readPush(context: Context, intent: Intent): NotificationInd? = try {
        parse(context, intent)
    } catch (t: Throwable) {
        Log.w(MmsReceiver.TAG, "MMS push could not be read; the library handles it", t)
        null
    }

    private fun save(context: Context, indication: NotificationInd, subscriptionId: Int): Saved = try {
        appendTransactionId(indication, subscriptionId)
        if (isDuplicate(context, indication)) {
            Log.i(MmsReceiver.TAG, "MMS push repeated; not downloaded again")
            Saved.Duplicate
        } else {
            persist(context, indication, subscriptionId)?.let { Saved.Row(it) } ?: Saved.Failed
        }
    } catch (t: Throwable) {
        Log.w(MmsReceiver.TAG, "MMS push could not be saved; the library handles it", t)
        Saved.Failed
    }

    private fun download(
        context: Context,
        saved: Uri,
        indication: NotificationInd,
        subscriptionId: Int,
        spam: SpamNotice?,
    ) {
        val rowId = runCatching { ContentUris.parseId(saved) }.getOrNull()
        try {
            val (location, transactionId) = lookup(context, saved)
            check(location.isNotEmpty()) { "no content location" }
            if (!firstDownloadOf(location)) {
                Log.i(MmsReceiver.TAG, "MMS download already started; not started again")
                return
            }
            // The row shows "Downloading", so a tap on "Tap to download" cannot start a second download.
            rowId?.let { tracker.begin(it, System.currentTimeMillis()) }
            try {
                start(context, location, transactionId, saved, subscriptionId)
            } catch (t: Throwable) {
                started.remove(location)
                rowId?.let(tracker::fail)
                throw t
            }
        } catch (t: Throwable) {
            // The notification-ind is saved, so the message shows as "Tap to download" and can be retried there.
            Log.w(MmsReceiver.TAG, "MMS download did not start", t)
            val from = MmsPushGate.cleanAddress(indication.from?.string.orEmpty())
            runCatching { onStartFailed(context, saved, from, spam) }
        }
    }

    private fun parse(context: Context, intent: Intent): NotificationInd? {
        val action = intent.action
        if (action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION &&
            action != Telephony.Sms.Intents.WAP_PUSH_RECEIVED_ACTION
        ) {
            return null
        }
        if (intent.type != MMS_MIME_TYPE) return null
        val data = intent.getByteArrayExtra(EXTRA_DATA) ?: return null
        // Loads carrier settings the library reads later (HTTP parameters); the defaults still work without them.
        runCatching { MmsConfig.init(context) }
        return PduParser(data).parse() as? NotificationInd
    }

    /** Carriers that want the transaction id on the content location (carrier config or MmsConfig) get it appended. */
    private fun appendTransactionId(indication: NotificationInd, subscriptionId: Int) {
        val fromCarrier = runCatching {
            SmsManagerFactory.createSmsManager(subscriptionId).carrierConfigValues?.getBoolean(CARRIER_TRANS_ID)
                ?: false
        }.getOrDefault(false)
        if (!fromCarrier && !MmsConfig.getTransIdEnabled()) return
        val location = indication.contentLocation ?: return
        val transactionId = indication.transactionId ?: return
        if (location.isEmpty() || location.last() != EQUALS) return
        indication.contentLocation = location + transactionId
    }

    /** The carrier re-sends a notification-ind; only the first one for a content location starts a download. */
    private fun firstDownloadOf(location: String): Boolean {
        val now = System.currentTimeMillis()
        started.entries.removeAll { now - it.value > STARTED_TTL_MS }
        return started.putIfAbsent(location, now) == null
    }

    internal companion object {
        const val MMS_MIME_TYPE = "application/vnd.wap.mms-message"
        private const val EXTRA_DATA = "data"
        private const val CARRIER_TRANS_ID = "enabledTransID"
        private const val EQUALS = '='.code.toByte()
        private const val STARTED_TTL_MS = 10 * 60 * 1000L
        private const val GROUP_MESSAGE_PREF = "group_message"
        private const val CACHE_PREFIX = "download."
        private const val HTTP_PARAMS = "httpParams"

        private val started = ConcurrentHashMap<String, Long>()

        /** The system reported a failed download: a tap on "Tap to try again" may start a new one at once. */
        fun clearStarted() = started.clear()

        /** Provider truth: the download of [location] failed; only that one may be started again at once. */
        fun forgetStarted(location: String) {
            started.remove(location)
        }

        /** Content locations whose download was started here, with the time; see [isStarted]. */
        fun isStarted(location: String, now: Long = System.currentTimeMillis()): Boolean =
            started[location]?.let { now - it < MmsDownloadRules.DOWNLOAD_TIMEOUT_MS } == true

        /**
         * mmslib 1.0.1 (PushReceiver.isDuplicateNotification) looks for an earlier row with the same transaction id.
         * The content location (after the transaction id was appended) is checked as well, because carriers re-send
         * the same notification-ind.
         */
        private fun isDuplicateNotification(context: Context, indication: NotificationInd): Boolean {
            val transactionId = indication.transactionId?.let { String(it) }
            val location = indication.contentLocation?.let { String(it) }
            val (selection, args) = when {
                transactionId != null && location != null ->
                    "tr_id = ? OR ct_l = ?" to arrayOf(transactionId, location)
                transactionId != null -> "tr_id = ?" to arrayOf(transactionId)
                location != null -> "ct_l = ?" to arrayOf(location)
                else -> return false
            }
            return context.contentResolver.query(Telephony.Mms.CONTENT_URI, arrayOf("_id"), selection, args, null)
                ?.use { it.count > 0 } ?: false
        }

        /**
         * Tells the user the MMS did not download and gives the saved row a thread, so it shows in a chat. A [spam]
         * notice gets its chat in Spam, read and seen (the notice names nobody, so it is still posted, R9).
         */
        private fun reportStartFailed(context: Context, saved: Uri, from: String, spam: SpamNotice?) {
            runCatching { context.showMmsDownloadFailedNotification() }
            if (from.isEmpty()) return
            if (spam != null) {
                runCatching { IncomingMms(AndroidMmsSpamStore(context)).revealNotice(saved, from, spam.cause) }
                    .onSuccess { return }
            }
            runCatching {
                val threadId = Telephony.Threads.getOrCreateThreadId(context, from)
                context.contentResolver.update(saved, ContentValues().apply { put("thread_id", threadId) }, null, null)
            }
        }

        private fun persistNotification(context: Context, indication: NotificationInd, subscriptionId: Int): Uri? {
            val group = runCatching { Transaction.settings?.group }.getOrNull()
                ?: PreferenceManager.getDefaultSharedPreferences(context).getBoolean(GROUP_MESSAGE_PREF, true)
            // As in the library, the thread is created here only when the MMS is not going to download by itself.
            val createThread = !NotificationTransaction.allowAutoDownload(context)
            return PduPersister.getPduPersister(context)
                .persist(indication, Telephony.Mms.Inbox.CONTENT_URI, createThread, group, null, subscriptionId)
        }

        /**
         * Asks the system to download the MMS into a cache file (served by mmslib's MmsFileProvider). The result goes
         * to [MmsReceiver] through [resultIntent] and this PendingIntent, which the system keeps for the app.
         */
        fun startDownload(
            context: Context,
            location: String,
            transactionId: String,
            notificationUri: Uri,
            subscriptionId: Int,
        ) {
            val name = "$CACHE_PREFIX${System.nanoTime() and Long.MAX_VALUE}.dat"
            val file = File(context.cacheDir, name)
            val uri = Uri.Builder()
                .scheme("content")
                .authority("${context.packageName}.MmsFileProvider")
                .path(name)
                .build()
            val pending = PendingIntent.getBroadcast(
                context,
                requestCode(notificationUri, location),
                resultIntent(context, file, location, transactionId, notificationUri, subscriptionId),
                pendingFlags(),
            )
            val overrides = Bundle().apply {
                MmsConfig.getHttpParams()?.takeIf { it.isNotEmpty() }?.let { putString(HTTP_PARAMS, it) }
            }
            try {
                SmsManagerFactory.createSmsManager(subscriptionId)
                    .downloadMultimediaMessage(context, location, uri, overrides, pending)
            } catch (t: Throwable) {
                file.delete()
                pending.cancel()
                throw t
            }
        }

        /** What [MmsReceiver] (mmslib's MmsReceivedReceiver) reads from the broadcast the system sends when done. */
        fun resultIntent(
            context: Context,
            file: File,
            location: String,
            transactionId: String,
            notificationUri: Uri,
            subscriptionId: Int,
        ): Intent = Intent(context, MmsReceiver::class.java)
            .putExtra(MmsReceivedReceiver.EXTRA_FILE_PATH, file.path)
            .putExtra(MmsReceivedReceiver.EXTRA_LOCATION_URL, location)
            .putExtra(MmsReceivedReceiver.EXTRA_TRANSACTION_ID, transactionId)
            .putExtra(MmsReceivedReceiver.EXTRA_TRIGGER_PUSH, true)
            .putExtra(MmsReceivedReceiver.EXTRA_URI, notificationUri)
            .putExtra(MmsReceivedReceiver.SUBSCRIPTION_ID, subscriptionId)

        /** One per saved notification-ind, so two downloads at once never share (and overwrite) a PendingIntent. */
        fun requestCode(notificationUri: Uri, location: String): Int {
            val id = runCatching { ContentUris.parseId(notificationUri) }.getOrDefault(-1L)
            return if (id >= 0) (id and Int.MAX_VALUE.toLong()).toInt() else location.hashCode()
        }

        /** Mutable: the system adds the download result (HTTP status) to it. Needs the flag from Android 12 on. */
        fun pendingFlags(): Int = PendingIntent.FLAG_CANCEL_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
    }
}
