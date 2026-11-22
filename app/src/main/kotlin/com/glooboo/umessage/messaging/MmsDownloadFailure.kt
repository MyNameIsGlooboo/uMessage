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
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.receivers.MmsPushDownload
import com.glooboo.umessage.spam.HistoryCheck
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.spam.mms.AndroidMmsSpamStore
import com.glooboo.umessage.spam.mms.IncomingMms
import com.glooboo.umessage.spam.mms.MmsDecisions
import com.glooboo.umessage.spam.mms.MmsSpamStore
import com.glooboo.umessage.spam.mms.NoticeMark
import com.glooboo.umessage.spam.mms.NoticeRow
import com.klinker.android.send_message.MmsReceivedReceiver
import org.fossify.commons.extensions.showErrorToast
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

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
    /** E-19: the sender's history check on the spot (R2); false when it failed. */
    private val history: (Context, String) -> Boolean = { c, sender -> HistoryCheck.onTheSpot(c, sender) },
    /** The one decision-log row of a failed MMS (decision, reason, rule). */
    private val log: (Context, IncomingMessage, String, String, Long?) -> Unit = { c, m, d, r, rule ->
        MmsDecisions.log(c, m, d, r, SOURCE, rule)
    },
) {

    /** Where a notice went after a failed download. */
    private sealed interface Outcome {
        /** No notice or no sender: nothing to show (the user is still told of a failed result). */
        data object Empty : Outcome

        /** J3-2, R2-3: the notice went while it was placed: its MMS was placed and logged by its result. */
        data object Gone : Outcome

        class Kept(val message: IncomingMessage, val decision: FilterDecision?, val inSpam: Boolean) : Outcome
    }

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
        val outcome = notice?.let { row ->
            runCatching { keepNotice(context, row, subscriptionId) }
                .onFailure { Log.w(TAG, "Failed MMS notice could not be given its chat", it) }
                .getOrNull()
        }
        if (outcome == Outcome.Gone) {
            // R2-3: its MMS arrived after all: no toast, no notification.
            Log.i(TAG, "MMS download failed; its notice is gone, its MMS was placed")
            return
        }
        val kept = outcome as? Outcome.Kept
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
     * (read and seen) or in Messages (unread).
     */
    private fun keepNotice(context: Context, row: Uri, subscriptionId: Int): Outcome {
        val store = store(context)
        val notice = store.noticeOf(row) ?: return Outcome.Empty
        if (notice.sender.isNotBlank()) return placeNotice(context, store, row, notice, subscriptionId)
        // No chat can hold it: the sweep must not try it again and again.
        runCatching { store.markNotice(row, NoticeMark.Skipped) }
        return Outcome.Empty
    }

    /** [keepNotice] for a notice with a sender. */
    private fun placeNotice(
        context: Context,
        store: MmsSpamStore,
        row: Uri,
        notice: NoticeRow,
        subscriptionId: Int,
    ): Outcome {
        val message = IncomingMessage(notice.sender, "", subscriptionId, isMms = true)
        // E-19: the history check on the spot first (it may allow the sender), then the filter is asked once.
        val historyChecked = runCatching { history(context, notice.sender) }.getOrDefault(false)
        val decision = probe(context, message)
        val probed = (decision as? FilterDecision.Block)?.let(IncomingMms::causeOf)
        // E-22: a notice counted in its Spam chat (its explicit mark) stays there and is not counted again.
        val alreadyInSpam = probed != null && IncomingMms.hasChat(notice.threadId) &&
            runCatching { store.markOf(row) == NoticeMark.Counted(notice.threadId) }.getOrDefault(false) &&
            runCatching { store.isSpamThreadOf(notice.threadId, notice.sender) }.getOrDefault(false)
        if (alreadyInSpam) return Outcome.Kept(message, decision, inSpam = true)
        // A failed history check leaves only a block rule (history never allows a blocked sender, as E-11).
        val cause = if (historyChecked) probed else probed.takeIf { it == SpamCause.BLOCKED }
        val ruleId = (decision as? FilterDecision.Block)?.ruleId?.takeIf { cause != null }
        val inSpam = IncomingMms(store).revealNotice(row, notice.sender, cause, ruleId) ?: return Outcome.Gone
        return Outcome.Kept(message, decision, inSpam)
    }

    /** E-13: one row for the failed MMS, with the place it got: BLOCK in Spam, DELIVER in Messages. */
    private fun logKept(context: Context, kept: Outcome.Kept) {
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
     * E-4, E-20: notices with no chat (a Spam notice that downloads keeps mmslib's dummy thread) saved between
     * [MAX_AGE_MS] and [MIN_AGE_MS] ago, whose download is not running (a reboot, a force stop or a lost result), are
     * placed as after a failed download, without a toast or a notification; at most [BATCH] per run, one run at a
     * time, notices that failed before last (R2-1). An expired notice or one without a sender is marked and left
     * alone. Called off the main thread at app start ([atAppStart]) and after each MMS push. Never throws.
     */
    fun sweep(context: Context, now: Long = System.currentTimeMillis()) {
        if (!sweeping.compareAndSet(false, true)) return
        try {
            val store = store(context)
            // E-25: the batch is taken after the filters, so skipped or running notices never hide an older lost one.
            val lost = runCatching { context.chatlessNotices(before = now - MIN_AGE_MS, after = now - MAX_AGE_MS) }
                .onFailure { Log.w(TAG, "Chatless MMS notices could not be read", it) }
                .getOrDefault(emptyList())
                .asSequence()
                .filterNot { MmsPushDownload.isStarted(it.location, now) }
                .map { it to runCatching { store.markOf(it.uri) }.getOrNull() }
                .filterNot { (_, mark) -> mark == NoticeMark.Skipped }
                // R2-1: a notice that failed before goes after the untried ones (stable: newest first in each).
                .sortedBy { (_, mark) -> mark is NoticeMark.Failed }
                .take(BATCH)
                .map { (notice, _) -> notice }
                .toList()
            lost.forEach { notice ->
                if (MmsDownloadRules.isExpired(notice.expirySeconds, now)) {
                    // Gone at the carrier: not brought up as a new unread row.
                    runCatching { store.markNotice(notice.uri, NoticeMark.Skipped) }
                } else {
                    runCatching { (keepNotice(context, notice.uri, notice.subscriptionId) as? Outcome.Kept) }
                        .onSuccess { kept -> kept?.let { logKept(context, it) } }
                        .onFailure { noteFailedTry(context, store, notice) }
                }
            }
            if (lost.isNotEmpty()) Log.i(TAG, "Chatless MMS notices swept: ${lost.size}")
        } finally {
            sweeping.set(false)
        }
    }

    /**
     * A notice whose placing threw counts one try (its mark, sorted after untried notices). R2-1: a notice is never
     * given up. From the [MAX_TRIES]th try on it fails open ([failOpen]); while even that fails it stays `Failed`
     * and is tried again by later sweeps, up to [MAX_AGE_MS]. A notice already counted or skipped keeps its mark.
     */
    private fun noteFailedTry(context: Context, store: MmsSpamStore, notice: ChatlessNotice) {
        Log.w(TAG, "Chatless MMS notice could not be placed")
        runCatching {
            val mark = store.markOf(notice.uri)
            if (mark != null && mark !is NoticeMark.Failed) return@runCatching
            val tries = ((mark as? NoticeMark.Failed)?.times ?: 0) + 1
            val shown = tries >= MAX_TRIES && runCatching { failOpen(context, store, notice) }.getOrDefault(false)
            store.markNotice(notice.uri, if (shown) null else NoticeMark.Failed(tries))
        }
    }

    /**
     * R2-1, R3-3: the notice in its sender's chat, unread and not silent, without the filter, with an ERROR row. False
     * when it could not be shown. A net for a store that recovers: placing a notice throws only from the store (the
     * probe and the history check do not throw), and this makes the same store calls, so it shows the notice only
     * when the store works again between the failed try and this one. The part of R2-1 that matters (never Skipped,
     * failed notices last) does not depend on it.
     *
     * Accepted (J3b): the mark is cleared here, so a later download or failed download of this notice writes its own
     * decision-log row, a second one after this ERROR row. That row says where the MMS really went, which this one
     * does not, as R3-2 writes a second row whenever the place differs from the logged one.
     */
    private fun failOpen(context: Context, store: MmsSpamStore, notice: ChatlessNotice): Boolean {
        val sender = store.noticeOf(notice.uri)?.sender?.takeIf { it.isNotBlank() } ?: return false
        val thread = store.threadOf(sender)
        if (!store.setThread(notice.uri, thread, silent = false)) return false
        runCatching { store.showChat(thread) }
        val message = IncomingMessage(sender, "", notice.subscriptionId, isMms = true)
        runCatching { log(context, message, IncomingMms.ERROR, PLACE_FAILED, null) }
        Log.w(TAG, "Chatless MMS notice shown unread after failed tries")
        return true
    }

    companion object {
        private const val SOURCE = "MMS_FAILED"

        /** R2-1: tries of one notice that end in an error before it is shown without the filter (fail open). */
        const val MAX_TRIES = 3

        private const val PLACE_FAILED = "PLACE_FAILED"

        /** A notice younger than this may still be downloading. */
        const val MIN_AGE_MS = 5 * 60 * 1000L

        /** E-20: an older notice is not swept (left from before the flag; most are expired anyway). */
        const val MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L

        /** E-20: most notices placed by one sweep. */
        const val BATCH = 20

        /** One sweep at a time (two pushes, or a push and the app start). */
        private val sweeping = AtomicBoolean(false)

        /** The app-start sweep; tests replace it. */
        @Volatile internal var startSweep: (Context) -> Unit = { MmsDownloadFailure().sweep(it) }

        /** E-20: the sweep at app start, flag on only; call it off the main thread. Never throws. */
        fun atAppStart(context: Context) {
            if (ProviderTruthFlag.enabled) runCatching { startSweep(context.applicationContext) }
        }

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
