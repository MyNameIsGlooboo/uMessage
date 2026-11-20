package com.glooboo.umessage.messaging

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.IntentCompat
import com.android.mms.transaction.DownloadManager
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.showMmsDownloadFailedNotification
import com.glooboo.umessage.receivers.MmsPushDownload
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.mms.AndroidMmsSpamStore
import com.glooboo.umessage.spam.mms.IncomingMms
import com.glooboo.umessage.spam.mms.MmsDecisions
import com.glooboo.umessage.spam.mms.MmsSpamStore
import com.klinker.android.send_message.MmsReceivedReceiver
import org.fossify.commons.extensions.showErrorToast
import java.io.File

private const val TAG = "uMessageReceive"

/**
 * Provider truth, answer 8: a picture message whose download failed keeps its notice, which shows "Tap to try again".
 *
 * mmslib 1.0.1 handles a failed result by deleting the notice on HTTP 400 or 404, and otherwise leaves a notice that
 * may have no chat at all (an automatic download saves it with mmslib's dummy thread, E-1), so the user could not
 * retry. Under the flag [com.glooboo.umessage.receivers.MmsReceiver] hands a result without a downloaded file here
 * instead of to the library, and also a result the library could not store (E-5): the notice stays, gets its chat
 * (in Spam when the sender's chat is there, E-9), the row turns "Tap to try again", and the user is told, by a toast
 * and the notification, except in Spam (E-2: a Spam chat never notifies). The library's own download record is
 * closed, so a retry is not refused.
 */
internal class MmsDownloadFailure(
    private val store: (Context) -> MmsSpamStore = { AndroidMmsSpamStore(it) },
    /** The sender's verdict, without writing or logging anything (E-13). */
    private val probe: (Context, IncomingMessage) -> FilterDecision? = { c, m -> MmsDecisions.probe(c, m) },
    private val notify: (Context) -> Unit = { it.showMmsDownloadFailedNotification() },
    private val toast: (Context) -> Unit = { it.showErrorToast(it.getString(R.string.couldnt_download_mms)) },
    private val finishLibraryDownload: (String) -> Unit = DownloadManager::finishDownload,
    private val tracker: MmsDownloadTracker = MmsDownloadTracker.shared,
    /** The one decision-log row of a failed MMS (decision, reason, rule). */
    private val log: (Context, IncomingMessage, String, String, Long?) -> Unit = { c, m, d, r, rule ->
        MmsDecisions.log(c, m, d, r, SOURCE, rule)
    },
) {

    /** Where a notice went after a failed download. */
    private class Kept(val message: IncomingMessage, val decision: FilterDecision?, val inSpam: Boolean)

    fun handle(context: Context, intent: Intent) {
        val location = intent.getStringExtra(MmsReceivedReceiver.EXTRA_LOCATION_URL).orEmpty()
        intent.getStringExtra(MmsReceivedReceiver.EXTRA_FILE_PATH)?.let { path -> runCatching { File(path).delete() } }
        if (location.isNotEmpty()) {
            runCatching { finishLibraryDownload(location) }
            MmsPushDownload.forgetStarted(location)
        } else {
            MmsPushDownload.clearStarted()
        }
        val notice = IntentCompat.getParcelableExtra(intent, MmsReceivedReceiver.EXTRA_URI, Uri::class.java)
        val id = notice?.let { runCatching { ContentUris.parseId(it) }.getOrNull() }?.takeIf { it >= 0 }
        // The row in an open chat turns into "Tap to try again"; without its id every running one does.
        if (id != null) tracker.fail(id) else tracker.failAllRunning()
        val subscriptionId =
            intent.getIntExtra(MmsReceivedReceiver.SUBSCRIPTION_ID, SubscriptionManager.INVALID_SUBSCRIPTION_ID)
        val kept = notice?.let { row ->
            runCatching { keepNotice(context, row, subscriptionId) }
                .onFailure { Log.w(TAG, "Failed MMS notice could not be given its chat", it) }
                .getOrNull()
        }
        val inSpam = kept?.inSpam == true
        kept?.let { logKept(context, it) }
        Log.w(TAG, "MMS download failed; its notice is kept")
        // E-2 (lead decision): a failure in a Spam chat posts nothing, neither the toast nor the notification.
        if (!inSpam) {
            runCatching { toast(context) }
            runCatching { notify(context) }
        }
    }

    /**
     * Gives the notice [row] its place after a failed download. The sender is judged again (E-9): a notice already in
     * the sender's Spam chat stays there and is not counted again; otherwise it is shown in the sender's chat, in Spam
     * (read and seen) or in Messages (unread). True when it is shown in Spam.
     */
    private fun keepNotice(context: Context, row: Uri, subscriptionId: Int): Kept? {
        val store = store(context)
        val notice = store.noticeOf(row) ?: return null
        if (notice.sender.isBlank()) return null
        val message = IncomingMessage(notice.sender, "", subscriptionId, isMms = true)
        val decision = probe(context, message)
        val cause = (decision as? FilterDecision.Block)?.let(IncomingMms::causeOf)
        // A read notice in its Spam chat was placed and counted already; a parked (unread) one is counted now.
        val alreadyInSpam = cause != null && notice.read && IncomingMms.hasChat(notice.threadId) &&
            runCatching { store.isSpamThreadOf(notice.threadId, notice.sender) }.getOrDefault(false)
        val inSpam = alreadyInSpam || IncomingMms(store).revealNotice(row, notice.sender, cause)
        return Kept(message, decision, inSpam)
    }

    /** E-13: one row for the failed MMS, with the place it got: BLOCK in Spam, DELIVER in Messages. */
    private fun logKept(context: Context, kept: Kept) {
        val decision = kept.decision
        val ruleId = (decision as? FilterDecision.Block)?.ruleId ?: (decision as? FilterDecision.Allow)?.ruleId
        runCatching {
            log(
                context, kept.message, if (kept.inSpam) IncomingMms.BLOCK else IncomingMms.DELIVER,
                decision?.reason?.name ?: "FILTER_FAILED", ruleId,
            )
        }
    }

    /**
     * E-4: notices with no chat that are older than [MIN_AGE_MS] and whose download is not running (a reboot, a force
     * stop or a lost result) are placed as after a failed download, without a toast or a notification. Call it off
     * the main thread, at app start and after each MMS push. Never throws.
     */
    fun sweep(context: Context, now: Long = System.currentTimeMillis()) {
        val lost = runCatching { context.chatlessNotices(before = now - MIN_AGE_MS) }.getOrDefault(emptyList())
        lost.filterNot { MmsPushDownload.isStarted(it.location, now) }.forEach { notice ->
            runCatching { keepNotice(context, notice.uri, notice.subscriptionId)?.let { logKept(context, it) } }
        }
        if (lost.isNotEmpty()) Log.i(TAG, "Chatless MMS notices placed: ${lost.size}")
    }

    companion object {
        private const val SOURCE = "MMS_FAILED"

        /** A notice younger than this may still be downloading. */
        const val MIN_AGE_MS = 5 * 60 * 1000L

        /**
         * The result brought no downloaded file (missing or empty): the download failed. A result without a file path
         * is left to the library, as before.
         */
        fun isFailed(intent: Intent): Boolean {
            val path = intent.getStringExtra(MmsReceivedReceiver.EXTRA_FILE_PATH) ?: return false
            val file = File(path)
            return !file.isFile || file.length() == 0L
        }
    }
}
