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
import com.glooboo.umessage.spam.HistoryCheck
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.mms.IncomingMms
import com.glooboo.umessage.spam.mms.MmsDecisions
import com.glooboo.umessage.spam.mms.MmsPushGate
import com.glooboo.umessage.spam.mms.MmsSpamStore
import com.glooboo.umessage.spam.mms.NoticeMark
import com.glooboo.umessage.spam.mms.NoticeVerdict
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
 * Provider truth (6.2): a notice whose chat is in Spam ([SpamNotice]) is saved the same way. Within the limits it
 * downloads here too and keeps mmslib's dummy thread, with no Spam row yet (the MMS may be a group one; it is placed
 * when it is stored, or found by the sweep). Otherwise it is shown in the sender's Spam chat at once, read and seen,
 * after the sender's history check (E-19), and stays a "Tap to download" row.
 */
@Suppress("TooGenericExceptionCaught") // Fail open: on any error the library or "Tap to download" takes over.
internal class MmsPushDownload(
    private val isDuplicate: (Context, NotificationInd) -> Boolean = { c, i -> isDuplicateNotification(c, i) },
    /** Saves the notice; the last argument (E-27): it must keep no chat, a Spam notice that downloads now. */
    private val persist: (Context, NotificationInd, Int, Boolean) -> Uri? = { c, i, sub, chatless ->
        persistNotification(c, i, sub, chatless)
    },
    /** Content location and transaction id of the saved notification-ind row. */
    private val lookup: (Context, Uri) -> Pair<String, String> = { c, uri ->
        PushReceiver.getContentLocation(c, uri) to PushReceiver.getTransactionId(c, uri)
    },
    private val start: (Context, String, String, Uri, Int) -> Unit = { c, loc, id, uri, sub ->
        startDownload(c, loc, id, uri, sub)
    },
    /**
     * Runs when the download could not be started: gives the saved row a thread and tells the user, except for a
     * notice shown in Spam (E-2: a Spam chat never notifies).
     */
    private val onStartFailed: (Context, Uri, String, SpamNotice?) -> Unit = { c, uri, from, spam ->
        reportStartFailed(c, uri, from, spam)
    },
    private val tracker: MmsDownloadTracker = MmsDownloadTracker.shared,
    /** Provider truth: the store and Spam table side of a [SpamNotice]. */
    private val incoming: (Context) -> IncomingMms = { IncomingMms(AndroidMmsSpamStore(it)) },
    /** Provider truth: the one decision-log row of a notice placed at save (decision, reason, rule). */
    private val log: (Context, SpamNotice, String) -> Unit = { c, spam, decision -> logNotice(c, spam, decision) },
    /** E-19: where a notice goes after the sender's history check on the spot (J3-4: with the reason). */
    private val recheck: (Context, SpamNotice) -> NoticeVerdict = { c, spam -> recheckedVerdict(c, spam) },
) {

    private sealed interface Saved {
        data object Duplicate : Saved
        data object Failed : Saved
        data class Row(val uri: Uri) : Saved
    }

    /** A Spam notice that is not downloaded now, after the history check: [Kept] in Spam, or [Allowed] (Messages). */
    private sealed interface AtSave {
        val notice: SpamNotice

        data class Kept(override val notice: SpamNotice) : AtSave
        data class Allowed(override val notice: SpamNotice) : AtSave
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
        return when (val saved = save(context, indication, subscriptionId, chatless = spam?.downloads == true)) {
            Saved.Duplicate -> true
            Saved.Failed -> false
            is Saved.Row -> {
                when (val atSave = spam?.takeIf { !it.downloads }?.let { recheckAtSave(context, it) }) {
                    null -> download(context, saved.uri, indication, subscriptionId, spam)
                    is AtSave.Kept -> revealInSpam(context, saved.uri, atSave.notice)
                    is AtSave.Allowed -> downloadAllowed(context, saved.uri, indication, subscriptionId, atSave.notice)
                }
                true
            }
        }
    }

    /**
     * A Spam notice that is not downloaded now: its final place is its chat, in Spam, read and seen (counted once).
     * Without its Spam row it shows in Messages, unread (fail open); if even that fails the user is told as for a
     * failed start (E-16). Its one decision-log row says where it went.
     */
    private fun revealInSpam(context: Context, saved: Uri, spam: SpamNotice) {
        val placer = incoming(context)
        // A blank sender has no chat to show it in: told as a failed start (it notifies), never retried.
        val first = if (spam.sender.isBlank()) null else runCatching {
            placer.revealNotice(saved, spam.sender, spam.cause, spam.ruleId)
        }.onFailure { Log.w(MmsReceiver.TAG, "Spam MMS notice could not be given its chat", it) }
        val inSpam = if (first != null && first.isSuccess) {
            first.getOrNull()
        } else {
            failOpenAtSave(placer, saved, spam) { onStartFailed(context, saved, spam.sender, null) }
        }
        // One row: BLOCK in Spam; ERROR without a rule when it failed open to Messages (as placeUnchecked); none when
        // the notice is gone (J3-2: its MMS was placed and logged by its result).
        when (inSpam) {
            true -> runCatching { log(context, spam, IncomingMms.BLOCK) }
            false -> runCatching { log(context, spam.copy(ruleId = null), IncomingMms.ERROR) }
            null -> Log.i(MmsReceiver.TAG, "Spam MMS notice gone before it was kept")
        }
        Log.i(MmsReceiver.TAG, "Spam MMS notice kept; not downloaded now")
    }

    /**
     * E-19: a notice kept at save is put in Spam only after the sender's history check; a sender the check allows is
     * a Messages MMS and is downloaded like any. J3-4: [AtSave.Allowed] carries the reason it was let in (the fresh
     * probe's, or HISTORY_CHECK_FAILED), and no rule. A recheck that throws keeps the push's cause.
     */
    private fun recheckAtSave(context: Context, notice: SpamNotice): AtSave {
        if (notice.sender.isBlank()) return AtSave.Kept(notice)
        val verdict = runCatching { recheck(context, notice) }.getOrDefault(NoticeVerdict(notice.cause, notice.reason))
        val cause = verdict.cause ?: return AtSave.Allowed(notice.copy(reason = verdict.reason, ruleId = null))
        return AtSave.Kept(notice.copy(cause = cause))
    }

    /**
     * E-19: a Spam notice kept at save whose sender the history check allows is downloaded as a Messages MMS. Its one
     * decision-log row is written where the MMS is placed; E-28: when the start fails it is written here (DELIVER,
     * with the reason it was let in), as the notice then stays in Messages.
     */
    private fun downloadAllowed(
        context: Context,
        saved: Uri,
        indication: NotificationInd,
        subscriptionId: Int,
        spam: SpamNotice,
    ) {
        if (download(context, saved, indication, subscriptionId, null)) return
        runCatching { log(context, spam, IncomingMms.DELIVER) }
    }

    private fun readPush(context: Context, intent: Intent): NotificationInd? = try {
        parse(context, intent)
    } catch (t: Throwable) {
        Log.w(MmsReceiver.TAG, "MMS push could not be read; the library handles it", t)
        null
    }

    private fun save(
        context: Context,
        indication: NotificationInd,
        subscriptionId: Int,
        chatless: Boolean,
    ): Saved = try {
        appendTransactionId(indication, subscriptionId)
        if (isDuplicate(context, indication)) {
            Log.i(MmsReceiver.TAG, "MMS push repeated; not downloaded again")
            Saved.Duplicate
        } else {
            persist(context, indication, subscriptionId, chatless)?.let { Saved.Row(it) } ?: Saved.Failed
        }
    } catch (t: Throwable) {
        Log.w(MmsReceiver.TAG, "MMS push could not be saved; the library handles it", t)
        Saved.Failed
    }

    /** Starts the download of [saved]. False when it could not be started (the user is told, see [onStartFailed]). */
    private fun download(
        context: Context,
        saved: Uri,
        indication: NotificationInd,
        subscriptionId: Int,
        spam: SpamNotice?,
    ): Boolean {
        val rowId = runCatching { ContentUris.parseId(saved) }.getOrNull()
        return try {
            val (location, transactionId) = lookup(context, saved)
            check(location.isNotEmpty()) { "no content location" }
            if (!firstDownloadOf(location)) {
                Log.i(MmsReceiver.TAG, "MMS download already started; not started again")
                return true
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
            true
        } catch (t: Throwable) {
            // The notification-ind is saved, so the message shows as "Tap to download" and can be retried there.
            Log.w(MmsReceiver.TAG, "MMS download did not start", t)
            val from = MmsPushGate.cleanAddress(indication.from?.string.orEmpty())
            runCatching { onStartFailed(context, saved, from, spam) }
            false
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
         * Tells the user the MMS did not download and gives the saved row a thread, so it shows in a chat. A [spam]
         * notice keeps (or gets) its chat in Spam, read and seen, and then posts nothing (E-2); if it ends in
         * Messages it is unread (E-3) and the user is told. J3-5: a [spam] notice gets its one decision-log row here.
         */
        internal fun reportStartFailed(
            context: Context,
            saved: Uri,
            from: String,
            spam: SpamNotice?,
            store: () -> MmsSpamStore = { AndroidMmsSpamStore(context) },
            notify: () -> Unit = { context.showMmsDownloadFailedNotification() },
            recheck: () -> NoticeVerdict = { spam?.let { recheckedVerdict(context, it) } ?: NoticeVerdict(null, "") },
            log: (SpamNotice, String) -> Unit = { notice, decision -> logNotice(context, notice, decision) },
        ) {
            if (spam != null && from.isNotEmpty()) {
                reportSpamStartFailed(SpamStartFailed(store, saved, from, spam, notify, recheck, log))
                return
            }
            runCatching(notify)
            if (from.isEmpty()) return
            runCatching {
                val threadId = Telephony.Threads.getOrCreateThreadId(context, from)
                context.contentResolver.update(saved, ContentValues().apply { put("thread_id", threadId) }, null, null)
            }
        }

        private fun persistNotification(
            context: Context,
            indication: NotificationInd,
            subscriptionId: Int,
            chatless: Boolean,
        ): Uri? {
            val group = runCatching { Transaction.settings?.group }.getOrNull()
                ?: PreferenceManager.getDefaultSharedPreferences(context).getBoolean(GROUP_MESSAGE_PREF, true)
            val createThread = savesWithChat(chatless) { NotificationTransaction.allowAutoDownload(context) }
            return PduPersister.getPduPersister(context)
                .persist(indication, Telephony.Mms.Inbox.CONTENT_URI, createThread, group, null, subscriptionId)
        }

        /**
         * Whether a notice is saved with its sender's chat. As in the library, only when the MMS is not going to
         * download by itself ([autoDownload] false). E-27: never a [chatless] one (a Spam notice that downloads now):
         * with automatic download refused (its setting, or mobile data suspended) it would otherwise land in the real
         * chat, unread, and the sweep could not find it after a lost result.
         */
        internal fun savesWithChat(chatless: Boolean, autoDownload: () -> Boolean): Boolean =
            !chatless && !autoDownload()

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
 * A notice kept at save that could not be put in Spam: unread in its sender's chat, or, without one, told as a failed
 * start ([toldAsFailedStart]). False then (Messages); null when the notice is gone.
 */
private fun failOpenAtSave(placer: IncomingMms, saved: Uri, spam: SpamNotice, toldAsFailedStart: () -> Unit): Boolean? {
    val shown = if (spam.sender.isBlank()) null else runCatching { placer.revealNotice(saved, spam.sender, null) }
    if (shown != null && shown.isSuccess && shown.getOrNull() == null) return null
    if (shown?.isSuccess != true) runCatching(toldAsFailedStart)
    return false
}

/** A failed start of the Spam notice [saved] of [from], with what [reportSpamStartFailed] needs. */
private class SpamStartFailed(
    val store: () -> MmsSpamStore,
    val saved: Uri,
    val from: String,
    val spam: SpamNotice,
    val notify: () -> Unit,
    val recheck: () -> NoticeVerdict,
    val log: (SpamNotice, String) -> Unit,
)

/**
 * The Spam side of [MmsPushDownload.reportStartFailed]: the notice is kept in Spam (posts nothing) or ends in Messages
 * unread (tells the user), and gets its one decision-log row (J3-5): BLOCK in Spam, DELIVER in Messages when the
 * history check let it in, ERROR when it failed open; none when it was counted, and so logged, before, or when it is
 * gone (J3-2).
 */
private fun reportSpamStartFailed(failed: SpamStartFailed): Unit = with(failed) {
    val kept = runCatching { keepSpamNotice(store(), saved, from, spam, recheck) }
        .onFailure { Log.w(MmsReceiver.TAG, "Spam MMS notice could not be given its chat", it) }
        .getOrNull()
    if (kept == SpamKept.Gone) return@with
    val placed = kept as? SpamKept.Placed
    if (placed?.inSpam != true) runCatching(notify)
    // Still no chat: as for a Messages notice, unread.
    if (placed == null) runCatching { store().run { setThread(saved, threadOf(from), silent = false) } }
    // Not placed (the store failed): failed open to Messages, so ERROR without a rule.
    val row = if (placed == null) spam.copy(ruleId = null) to IncomingMms.ERROR else placed.row
    if (row != null) runCatching { log(row.first, row.second) }
}

/** Where [keepSpamNotice] put a notice. */
private sealed interface SpamKept {
    /** J3-2: the notice went meanwhile. */
    data object Gone : SpamKept

    /** [row]: its decision-log row (notice, decision); null when it was counted, and so logged, before. */
    data class Placed(val inSpam: Boolean, val row: Pair<SpamNotice, String>?) : SpamKept
}

/**
 * The notice [saved] in the sender's chat. A notice counted in its Spam chat (its explicit mark, E-22) stays;
 * otherwise it is placed now, with the cause after the history check ([recheck], E-19), and counted once.
 */
private fun keepSpamNotice(
    store: MmsSpamStore,
    saved: Uri,
    from: String,
    spam: SpamNotice,
    recheck: () -> NoticeVerdict,
): SpamKept {
    val notice = store.noticeOf(saved)
    val counted = notice != null && IncomingMms.hasChat(notice.threadId) &&
        store.markOf(saved) == NoticeMark.Counted(notice.threadId) && store.isSpamThreadOf(notice.threadId, from)
    if (counted) return SpamKept.Placed(inSpam = true, row = null)
    val verdict = recheck()
    val cause = verdict.cause
    val inSpam = IncomingMms(store).revealNotice(saved, from, cause, spam.ruleId.takeIf { cause != null })
        ?: return SpamKept.Gone
    val row = when {
        inSpam -> spam.copy(cause = cause ?: spam.cause) to IncomingMms.BLOCK
        cause == null -> spam.copy(reason = verdict.reason, ruleId = null) to IncomingMms.DELIVER
        // Judged Spam, but its Spam row failed: fail open, as placeUnchecked logs it.
        else -> spam.copy(ruleId = null) to IncomingMms.ERROR
    }
    return SpamKept.Placed(inSpam, row)
}

/** E-19, J3-4: [spam]'s place after the sender's history check on the spot, with the filter asked again. */
private fun recheckedVerdict(context: Context, spam: SpamNotice): NoticeVerdict = IncomingMms.noticeVerdict(
    spam.cause,
    history = { HistoryCheck.onTheSpot(context, spam.sender) },
    probe = { MmsDecisions.probe(context, IncomingMessage(spam.sender, "", spam.subscriptionId, isMms = true)) },
)

/** The decision-log row of a notice placed at save (E-13: written once, with the place it got). */
private fun logNotice(context: Context, spam: SpamNotice, decision: String) {
    val message = IncomingMessage(spam.sender, "", spam.subscriptionId, isMms = true)
    MmsDecisions.log(context, message, decision, spam.reason, "MMS_PUSH", spam.ruleId)
}
