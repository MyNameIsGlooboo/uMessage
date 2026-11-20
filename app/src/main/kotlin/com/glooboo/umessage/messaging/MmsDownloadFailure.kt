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
import com.glooboo.umessage.spam.engine.SpamEngine
import com.glooboo.umessage.spam.mms.AndroidMmsSpamStore
import com.glooboo.umessage.spam.mms.IncomingMms
import com.glooboo.umessage.spam.mms.MmsSpamStore
import com.klinker.android.send_message.MmsReceivedReceiver
import org.fossify.commons.extensions.showErrorToast
import java.io.File

private const val TAG = "uMessageReceive"

/**
 * Provider truth, answer 8: a picture message whose download failed keeps its notice, which shows "Tap to try again".
 *
 * mmslib 1.0.1 handles a failed result by deleting the notice on HTTP 400 or 404, and otherwise leaves a notice that
 * may have no chat at all (an automatic download saves it without one), so the user could not retry. Under the flag
 * [com.glooboo.umessage.receivers.MmsReceiver] hands a result without a downloaded file here instead of to the
 * library: the notice stays, gets its chat (in Spam when the sender's chat is there), the row turns "Tap to try
 * again", and the user is told, without a toast for Spam. The library's own download record is closed, so a retry is
 * not refused.
 */
internal class MmsDownloadFailure(
    private val store: (Context) -> MmsSpamStore = { AndroidMmsSpamStore(it) },
    /** The sender's verdict, without writing anything. */
    private val probe: (Context, IncomingMessage) -> FilterDecision? = { c, m ->
        SpamEngine.evaluate(c, m, source = "MMS_FAILED")
    },
    private val notify: (Context) -> Unit = { it.showMmsDownloadFailedNotification() },
    private val toast: (Context) -> Unit = { it.showErrorToast(it.getString(R.string.couldnt_download_mms)) },
    private val finishLibraryDownload: (String) -> Unit = DownloadManager::finishDownload,
    private val tracker: MmsDownloadTracker = MmsDownloadTracker.shared,
) {

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
        val inSpam = notice != null && runCatching { keepNotice(context, notice, subscriptionId) }
            .onFailure { Log.w(TAG, "Failed MMS notice could not be given its chat", it) }
            .getOrDefault(false)
        Log.w(TAG, "MMS download failed; its notice is kept")
        // For Spam no toast (answer 8). The notification names nobody and stays for now (R9).
        if (!inSpam) runCatching { toast(context) }
        runCatching { notify(context) }
    }

    /** Gives the notice [row] its chat when it has none yet. True when it is shown in Spam. */
    private fun keepNotice(context: Context, row: Uri, subscriptionId: Int): Boolean {
        val store = store(context)
        val notice = store.noticeOf(row) ?: return false
        if (notice.sender.isBlank()) return false
        if (notice.threadId > 0) {
            // It has its chat already (an earlier failure or a failed start): only tell whether that is Spam.
            return runCatching { store.isSpamThreadOf(notice.threadId, notice.sender) }.getOrDefault(false)
        }
        val decision = probe(context, IncomingMessage(notice.sender, "", subscriptionId, isMms = true))
        val cause = (decision as? FilterDecision.Block)?.let(IncomingMms::causeOf)
        return IncomingMms(store).revealNotice(row, notice.sender, cause)
    }

    companion object {
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
