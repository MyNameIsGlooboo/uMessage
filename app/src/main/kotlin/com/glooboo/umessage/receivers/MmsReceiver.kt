package com.glooboo.umessage.receivers

import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import androidx.core.content.IntentCompat
import com.bumptech.glide.Glide
import com.klinker.android.send_message.MmsReceivedReceiver
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.helpers.ensureBackgroundThread
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.getConversations
import com.glooboo.umessage.extensions.getMMS
import com.glooboo.umessage.extensions.getLatestMMS
import com.glooboo.umessage.extensions.getNameFromAddress
import com.glooboo.umessage.extensions.insertOrUpdateConversation
import com.glooboo.umessage.extensions.isImageMimeType
import com.glooboo.umessage.extensions.isVideoMimeType
import com.glooboo.umessage.extensions.shouldUnarchive
import com.glooboo.umessage.extensions.showReceivedMessageNotification
import com.glooboo.umessage.extensions.updateConversationArchivedStatus
import com.glooboo.umessage.helpers.NotifyContext
import com.glooboo.umessage.helpers.OtpCodeExtractor
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.helpers.refreshConversations
import com.glooboo.umessage.helpers.showMmsDownloadFailedNotification
import com.glooboo.umessage.helpers.refreshMessages
import com.glooboo.umessage.messaging.MmsDownloadFailure
import com.glooboo.umessage.messaging.MmsDownloadTracker
import com.glooboo.umessage.messaging.noticeSavedAt
import com.glooboo.umessage.messaging.storedMmsFor
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.HistoryCheck
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.MmsMedia
import com.glooboo.umessage.spam.data.ForwardDelivery
import com.glooboo.umessage.spam.engine.ForwardCandidate
import com.glooboo.umessage.spam.engine.Judgement
import com.glooboo.umessage.spam.engine.SpamEngine
import com.glooboo.umessage.spam.engine.UnknownNotifyPolicy
import com.glooboo.umessage.spam.mms.AndroidMmsSpamStore
import com.glooboo.umessage.spam.mms.BlockedMmsStore
import com.glooboo.umessage.spam.mms.IncomingMms
import com.glooboo.umessage.spam.mms.MmsDecisions
import com.glooboo.umessage.spam.mms.MmsPlace
import com.glooboo.umessage.spam.mms.MmsSenderCheck
import com.glooboo.umessage.spam.mms.NoticeMark
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.data.SpamSettingsStore
import com.glooboo.umessage.spam.data.UncachedChats
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The receiver mmslib sends "MMS downloaded" to (the manifest entry). mmslib's own receiver has a final onReceive
 * that does all its work (persist the MMS, filter it, then call [MmsDownloadHandler.onMessageReceived]) on a bare
 * thread without a PendingResult, so the process could be killed before the notification and the conversation
 * update happen. This wrapper holds the broadcast open with goAsync() until that thread has finished, bounded by
 * [LIBRARY_WORK_TIMEOUT_MS] (well inside the background broadcast limit).
 *
 * Provider truth (answer 8): a result without a downloaded file never reaches the library, which could delete the
 * notice; [MmsDownloadFailure] keeps it instead. The same runs when the library ended without storing the MMS and
 * without an error (E-5), or with one (it then posts nothing itself, E-2).
 */
class MmsReceiver : BroadcastReceiver() {

    /** Provider truth: what is done with a failed download. */
    internal var failure = MmsDownloadFailure()

    /** E-17: the MMS row the library stored for a download (transaction id, location, since); null when none. */
    internal var storedRow: (Context, String, String, Long) -> Uri? = { c, id, location, since ->
        c.storedMmsFor(id, location, since)
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        if (ProviderTruthFlag.enabled && MmsDownloadFailure.isFailed(intent)) {
            keepFailedNotice(context.applicationContext, intent, pending)
            return
        }
        val handler = MmsDownloadHandler()
        handler.pduPath = intent.getStringExtra(MmsReceivedReceiver.EXTRA_FILE_PATH)
        if (ProviderTruthFlag.enabled) {
            handler.noticeUri = IntentCompat.getParcelableExtra(intent, MmsReceivedReceiver.EXTRA_URI, Uri::class.java)
        }
        try {
            handler.onReceive(context, intent)
        } catch (t: Throwable) {
            Log.e(TAG, "mmslib could not start handling a downloaded MMS", t)
            runCatching { pending.finish() }
            return
        }
        try {
            ensureBackgroundThread {
                try {
                    val ended = handler.worker.await(LIBRARY_WORK_TIMEOUT_MS)
                    if (!ended) {
                        Log.w(TAG, "Downloaded MMS still being handled after $LIBRARY_WORK_TIMEOUT_MS ms")
                    } else if (ProviderTruthFlag.enabled && !handler.stored) {
                        afterUnreported(context.applicationContext, intent, handler)
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Downloaded MMS could not be finished", t)
                } finally {
                    runCatching { pending.finish() }
                }
            }
        } catch (t: Throwable) {
            runCatching { pending.finish() }
        }
    }

    /**
     * The library ended without storing the MMS or reporting an error. E-17: it may have stored the row and then
     * failed (the update or the notice delete threw): that MMS is placed. Otherwise (E-5: a file it could not read or
     * store) it is a failed download.
     */
    private fun afterUnreported(context: Context, intent: Intent, handler: MmsDownloadHandler) {
        val transactionId = intent.getStringExtra(MmsReceivedReceiver.EXTRA_TRANSACTION_ID).orEmpty()
        val location = intent.getStringExtra(MmsReceivedReceiver.EXTRA_LOCATION_URL).orEmpty()
        val notice = IntentCompat.getParcelableExtra(intent, MmsReceivedReceiver.EXTRA_URI, Uri::class.java)
        val since = notice?.let { runCatching { context.noticeSavedAt(it) }.getOrNull() } ?: 0L
        val stored = runCatching { storedRow(context, transactionId, location, since) }.getOrNull()
        if (stored != null) handler.onMessageReceived(context, stored) else failure.handle(context, intent)
    }

    private fun keepFailedNotice(context: Context, intent: Intent, pending: BroadcastReceiver.PendingResult) {
        try {
            ensureBackgroundThread {
                try {
                    failure.handle(context, intent)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed MMS download could not be handled", t)
                } finally {
                    runCatching { pending.finish() }
                }
            }
        } catch (t: Throwable) {
            runCatching { pending.finish() }
        }
    }

    internal companion object {
        const val TAG = "uMessageReceive"
        const val LIBRARY_WORK_TIMEOUT_MS = 30_000L
    }
}

/**
 * Remembers the thread a library callback runs on, so a caller can wait for that thread to finish. mmslib calls
 * every hook of one download on the same worker thread, so the first [mark] identifies it.
 */
internal class WorkerThreadTracker {
    private companion object {
        const val FIRST_HOOK_WAIT_MS = 5_000L
    }

    private val started = CountDownLatch(1)

    @Volatile private var worker: Thread? = null

    fun mark() {
        if (worker != null) return
        synchronized(this) {
            if (worker == null) {
                worker = Thread.currentThread()
                started.countDown()
            }
        }
    }

    /** Waits until the marked thread has ended. False when that did not happen within [timeoutMs]. */
    fun await(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        // If mmslib fails before it calls any hook, nothing ever marks a thread: give up sooner than the full wait.
        if (!started.await(minOf(timeoutMs, FIRST_HOOK_WAIT_MS), TimeUnit.MILLISECONDS)) return false
        val thread = worker ?: return false
        if (thread === Thread.currentThread()) return true
        val leftMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
        if (leftMs > 0) thread.join(leftMs)
        return !thread.isAlive
    }
}

/** mmslib's download callbacks. Created by [MmsReceiver] for each download, never registered on its own. */
internal class MmsDownloadHandler : MmsReceivedReceiver() {

    val worker = WorkerThreadTracker()

    /** The downloaded PDU mmslib is handling (its cache file), so a blocked MMS can be kept for "Not spam". */
    @Volatile var pduPath: String? = null

    /** Provider truth: the notice row this download belongs to (the library deletes it once the MMS is stored). */
    @Volatile var noticeUri: Uri? = null

    /** Provider truth: the store and Spam table side of the placement. */
    var incomingMms: (Context) -> IncomingMms = { IncomingMms(AndroidMmsSpamStore(it)) }

    /** Provider truth: the sender checks of one MMS ([AppSenderCheck]). */
    var senderCheck: (Context, IncomingMessage, Long) -> MmsSenderCheck = { c, m, t -> AppSenderCheck(c, m, t) }

    /** Provider truth: what is done with a placed MMS; tests replace these. */
    var announce: (Context, Message, String, DecisionReason?) -> Unit = { c, m, a, r -> handleMmsMessage(c, m, a, r) }
    var forwardOf: (Context, ForwardCandidate) -> Unit = { c, f -> ForwardDelivery.onInboxMessage(c, f) }
    var updateChatOf: (Context, Message, Boolean) -> Unit = { c, m, unarchive -> updateChat(c, m, unarchive) }

    /** Provider truth (E-5): the library stored the MMS ([onMessageReceived] ran). */
    @Volatile var stored = false
        private set

    private val pendingAddress = ThreadLocal<String>()

    /** Why the filter let this MMS in; read by [onMessageReceived], which runs on the same worker thread. */
    @Volatile private var allowReason: DecisionReason? = null

    /** Provider truth: sender, text and SIM of the MMS, for [onMessageReceived] (same worker thread). */
    @Volatile private var received: IncomingMessage? = null

    /** Provider truth: the chat the notice was shown in before the download, read before the library deletes it. */
    @Volatile private var noticeThread: Long? = null

    /** Provider truth (E-8, E-22): the Spam chat the notice was counted in (its explicit mark), or null. */
    @Volatile private var noticeCountedIn: Long? = null

    /** J3-1: the notice was not counted when first read (no mark, or a failed try), so a later mark is new. */
    @Volatile private var noticeUnmarked = false

    override fun isAddressBlocked(context: Context, address: String?): Boolean {
        worker.mark()
        pendingAddress.set(address.orEmpty().substringBefore("/TYPE="))
        if (ProviderTruthFlag.enabled) {
            // E-1: a notice saved for an automatic download has mmslib's dummy thread, which is no chat.
            val notice = noticeUri?.let { uri ->
                runCatching { AndroidMmsSpamStore(context).noticeOf(uri) }.getOrNull()
            }
            noticeThread = notice?.threadId?.takeIf(IncomingMms::hasChat)
            val mark = noticeUri?.let { uri -> runCatching { AndroidMmsSpamStore(context).markOf(uri) } }
            noticeUnmarked = mark != null && mark.isSuccess &&
                mark.getOrNull().let { it == null || it is NoticeMark.Failed }
            noticeCountedIn = (mark?.getOrNull() as? NoticeMark.Counted)?.threadId?.takeIf { it == noticeThread }
        }
        return false
    }

    override fun isContentBlocked(context: Context, content: String?): Boolean {
        worker.mark()
        val address = pendingAddress.get().orEmpty()
        pendingAddress.remove()
        val appContext = context.applicationContext
        // The SIM the MMS arrived on, so per-SIM rules match; INVALID (all-SIM rules only) if it can't be found.
        // Never the default SIM: that would apply SIM 1's rules to an MMS that came in on SIM 2.
        val incoming = IncomingMessage(
            address = address,
            body = content.orEmpty(),
            subscriptionId = MmsSubscriptions.find(appContext, address),
            isMms = true,
        )
        if (ProviderTruthFlag.enabled) {
            // 6.2: always false. The library must store the MMS; where it is shown is decided once it is stored and
            // its recipients are known (a group stays in the group), in [onMessageReceived].
            received = incoming
            return false
        }
        val decision = SpamEngine.evaluate(appContext, incoming, source = "MMS_CONTENT")
        val block = decision as? FilterDecision.Block ?: run {
            allowReason = (decision as? FilterDecision.Allow)?.reason
            SpamEngine.allowSenderOfKeyword(appContext, incoming, decision)
            return false
        }
        return storeBlocked(appContext, incoming, block)
    }

    /**
     * Keeps the downloaded MMS in app-private storage with the blocked row (SE-29), so "Not spam" can restore its
     * pictures. On a block mmslib deletes the notification-ind and leaves its cache file behind; once the MMS is safely
     * kept that file is deleted too. False (deliver normally) only when the row itself could not be stored.
     */
    private fun storeBlocked(context: Context, incoming: IncomingMessage, block: FilterDecision.Block): Boolean {
        val file = pduPath?.let(::File)
        val pdu = runCatching { file?.takeIf { it.isFile }?.readBytes() }.getOrNull()
        val name = pdu?.let { BlockedMmsStore.save(context, it) }
        val id = SpamEngine.storeBlockedId(
            context, incoming, block,
            media = if (name != null) MmsMedia.STORED else MmsMedia.MISSING,
            mediaFile = name,
        )
        if (id == null) {
            name?.let { BlockedMmsStore.delete(context, listOf(it)) }
            return false
        }
        runCatching { file?.delete() }
        return true
    }

    /**
     * Runs on mmslib's worker thread, which [MmsReceiver] keeps the broadcast open for, so the work is done right
     * here instead of on yet another thread. The MMS is already in the system provider; nothing here may crash.
     */
    override fun onMessageReceived(context: Context, messageUri: Uri?) {
        worker.mark()
        stored = true
        try {
            val mms = if (messageUri != null) {
                context.getMMS(messageUri)
            } else {
                context.getLatestMMS()
            } ?: return

            val address = mms.getSender()?.phoneNumbers?.firstOrNull()?.normalizedNumber ?: mms.senderPhoneNumber
            if (ProviderTruthFlag.enabled) {
                placeAndAnnounce(context.applicationContext, mms, address)
            } else {
                handleMmsMessage(context, mms, address, legacyReason(context, address))
                // A blocked MMS never gets here (isContentBlocked kept it out of the inbox).
                forward(context, mms, address)
            }
        } catch (t: Throwable) {
            Log.e(MmsReceiver.TAG, "Downloaded MMS could not be announced", t)
        }
    }

    /**
     * Provider truth, 6.2: the MMS is in the store, unread, in the chat of its recipients. A group stays there; a
     * one-to-one MMS is judged like an SMS ([IncomingMms.placeDownloaded]). Only an MMS shown in Messages is
     * announced and forwarded; any error ends in Messages, announced (fail open), and one judged Spam is not
     * forwarded even then.
     */
    internal fun placeAndAnnounce(context: Context, mms: Message, address: String) {
        val incoming = (received ?: IncomingMessage(address, mms.body, mms.subscriptionId, isMms = true))
            .copy(receivedAt = mms.millis())
        val sender = incoming.address.ifBlank { address }
        val message = incoming.copy(address = sender)
        val placer = incomingMms(context)
        // J3-1: the sweep may have placed and counted the notice while the library stored the MMS (after
        // isAddressBlocked read no mark): read the mark again, so the MMS is not counted a second time, and the chat
        // it was counted in is the notice's chat (lowered or forgotten when the MMS lands elsewhere).
        var sweepLogged = false
        if (noticeUnmarked && noticeCountedIn == null) {
            noticeUri?.let { countedChatOf(context, it) }?.let { thread ->
                noticeCountedIn = thread
                noticeThread = noticeThread ?: thread
                sweepLogged = true
            }
        }
        // The sweep wrote this message's decision-log row when it placed the notice: the placement's rows are held.
        val unlogged = if (sweepLogged) Unlogged(senderCheck(context, message, mms.threadId)) else null
        val place = placer.placeDownloaded(
            row = ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, mms.id),
            threadId = mms.threadId,
            sender = sender,
            recipients = recipientsOf(mms),
            check = unlogged ?: senderCheck(context, message, mms.threadId),
            noticeCounted = noticeCountedIn == mms.threadId,
        )
        // R3-2: the sweep's BLOCK row stands for the MMS only when it ends in Spam in the chat the notice was counted
        // in; anywhere else (a group, Messages, a failed check) the placement's own rows are written.
        if (place !is MmsPlace.Spam || noticeCountedIn != mms.threadId) unlogged?.flush()
        placer.forgetNoticeChat(noticeThread, mms.threadId, noticeCountedIn)
        // The notice is gone (the library deleted it once the MMS was stored): its mark is not needed any more.
        noticeUri?.let { uri -> runCatching { AndroidMmsSpamStore(context).markNotice(uri, null) } }
        when (place) {
            is MmsPlace.Messages -> {
                announce(context, mms, address, place.reason)
                // A Spam MMS that failed open to Messages is announced but never forwarded.
                if (place.forwards) forward(context, mms, address)
            }
            // No notification, no unarchive, no forward; the chat and the lists are updated.
            MmsPlace.QuietGroup, is MmsPlace.Spam -> updateChatOf(context, mms, false)
        }
        Log.i(MmsReceiver.TAG, "Downloaded MMS placed: ${place.javaClass.simpleName}")
    }

    /** Why a 0.4.14 MMS was let in, for its notification: a number written to before counts as known. */
    private fun legacyReason(context: Context, address: String): DecisionReason? {
        val reason = allowReason
        return if (reason == DecisionReason.MODE_OFF && SpamEngine.hasWrittenTo(context, address)) {
            DecisionReason.KNOWN_CONVERSATION
        } else {
            reason
        }
    }

    /** Its text is forwarded; the pictures are not (forwards are plain SMS). */
    private fun forward(context: Context, mms: Message, address: String) {
        forwardOf(
            context.applicationContext,
            ForwardCandidate(
                address = address,
                body = mms.body,
                subscriptionId = mms.subscriptionId,
                isMms = true,
                receivedAt = mms.millis(),
                incoming = mms.isReceivedMessage(),
            ),
        )
    }

    override fun onError(context: Context, error: String) {
        worker.mark()
        // Provider truth: MmsReceiver runs the failure path once the library ended, which knows whether the notice is
        // in a Spam chat (E-2: then nothing is posted).
        if (ProviderTruthFlag.enabled) return
        context.showErrorToast(context.getString(R.string.couldnt_download_mms))
        // The toast is gone before anyone looks: the notification stays (I10).
        runCatching { context.showMmsDownloadFailedNotification() }
        // Q40: the "Downloading" rows in an open chat turn into "Tap to try again" (the error names no message).
        MmsDownloadTracker.shared.failAllRunning()
        MmsPushDownload.clearStarted()
    }

    private fun handleMmsMessage(
        context: Context,
        mms: Message,
        address: String,
        reason: DecisionReason?,
    ) {
        val size = context.resources.getDimension(R.dimen.notification_large_icon_size).toInt()
        // Only a picture or a video has a thumbnail to show; a text-only MMS skips the load.
        val imageUri = mms.attachment?.attachments?.firstOrNull { it.mimetype.isPreviewable() }?.getUri()
        val glideBitmap = if (imageUri == null) null else try {
            Glide.with(context)
                .asBitmap()
                .load(imageUri)
                .centerCrop()
                .into(size, size)
                .get(BITMAP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            null
        }

        val senderName = runCatching {
            context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true).use {
                context.getNameFromAddress(address, it)
            }
        }.getOrNull() ?: address

        val setting = runCatching { SpamSettingsStore(context).get().unknownInboxNotify }
            .getOrDefault(SpamSettings().unknownInboxNotify)
        val notify = NotifyContext(
            mode = UnknownNotifyPolicy.modeFor(reason, setting, OtpCodeExtractor.extract(mms.body) != null),
            reason = reason,
            isGroup = mms.participants.size > 1,
        )
        runCatching {
            context.showReceivedMessageNotification(
                messageId = mms.id,
                isMms = true,
                address = address,
                senderName = senderName,
                // A picture-only message has no text: a label instead of an empty notification (B17).
                body = mms.body.ifBlank { context.getString(R.string.attachment) },
                threadId = mms.threadId,
                bitmap = glideBitmap,
                // The SIM's own channel on a dual-SIM phone (B17).
                subscriptionId = mms.subscriptionId,
                notify = notify,
            )
        }.onFailure { Log.e(MmsReceiver.TAG, "MMS notification failed", it) }

        updateChat(context, mms, unarchive = true)
    }

    /** The cached chat of [mms] and the open screens; [unarchive]: as the setting says (not for a silent MMS). */
    private fun updateChat(context: Context, mms: Message, unarchive: Boolean) {
        val cached = runCatching {
            context.getConversations(mms.threadId).firstOrNull()?.let { context.insertOrUpdateConversation(it) } != null
        }.getOrDefault(false)
        // R3-1: as for a text, a chat that could not be cached is cached again at once.
        if (!cached && ProviderTruthFlag.enabled) UncachedChats.request(context, mms.threadId)
        runCatching {
            if (unarchive && context.shouldUnarchive()) {
                context.updateConversationArchivedStatus(mms.threadId, false)
            }
        }
        runCatching { refreshMessages() }
        runCatching { refreshConversations(mms.threadId) }
    }

    private companion object {
        const val BITMAP_TIMEOUT_MS = 5_000L
    }
}

/**
 * J3-1: the sender checks of an MMS whose notice the sweep placed, counted and logged. R3-2: its decision-log rows are
 * held, not dropped: [flush] writes them (the MMS ended elsewhere than the sweep's row says); otherwise they go.
 */
private class Unlogged(private val check: MmsSenderCheck) : MmsSenderCheck by check {
    private val held = mutableListOf<Triple<String, String, Long?>>()

    /** As [MmsSenderCheck.judge] (facts first); the rows it would write are held. */
    override fun judge(): Judgement? = check.judgeQuietly().also { hold(it) }

    override fun log(decision: String, reason: String, ruleId: Long?) {
        held += Triple(decision, reason, ruleId)
    }

    /** Writes the held rows, in order, once. */
    fun flush() {
        held.forEach { (decision, reason, ruleId) -> runCatching { check.log(decision, reason, ruleId) } }
        held.clear()
    }

    /** The rows [SpamEngine.judge] writes for [judgement]: a failed judge, a failed fact write, the verdict. */
    private fun hold(judgement: Judgement?) {
        if (judgement == null) {
            held += Triple(IncomingMms.ERROR, FILTER_FAILED, null)
            return
        }
        if (judgement.failOpen) held += Triple(IncomingMms.ERROR, "FACT_WRITE_FAILED", null)
        when (val decision = judgement.decision()) {
            is FilterDecision.Block -> held += Triple(IncomingMms.BLOCK, decision.reason.name, decision.ruleId)
            is FilterDecision.Allow -> held += Triple(ALLOW, decision.reason.name, decision.ruleId)
        }
    }

    private companion object {
        const val ALLOW = "ALLOW"
        const val FILTER_FAILED = "FILTER_FAILED"
    }
}

/** J3-1: the Spam chat the notice [uri] was counted in (its explicit mark), or null. */
private fun countedChatOf(context: Context, uri: Uri): Long? =
    runCatching { AndroidMmsSpamStore(context).markOf(uri) as? NoticeMark.Counted }.getOrNull()?.threadId

/**
 * E-15: the recipients of [mms]'s chat as the lists count them (is_group_conversation: more than one, the user's own
 * number too); null when one could not be read (E-10).
 */
private fun recipientsOf(mms: Message): Int? = IncomingMms.recipients(
    mms.participants.map { contact ->
        contact.phoneNumbers.firstOrNull()?.let { it.normalizedNumber.ifBlank { it.value } }.orEmpty()
    }
)

/**
 * Provider truth: the sender checks of one downloaded MMS ([message], stored in [threadId]) on the app's filter. Only
 * [MmsSenderCheck.judge] and [MmsSenderCheck.log] write a decision-log row, so an MMS has one (E-13).
 */
internal class AppSenderCheck(
    private val context: Context,
    private val message: IncomingMessage,
    private val threadId: Long,
) : MmsSenderCheck {
    override fun probe(): FilterDecision? = MmsDecisions.probe(context, message.copy(body = ""))

    override fun judge(): Judgement? = SpamEngine.judge(context, message, "MMS_CONTENT")

    /** J3-1: as [judge] (facts first), without the decision-log row. */
    override fun judgeQuietly(): Judgement? = SpamEngine.judge(context, message, "MMS_CONTENT", writeLog = false)

    override fun historyChecked(): Boolean = HistoryCheck.onTheSpot(context, message.address, threadId)

    override fun log(decision: String, reason: String, ruleId: Long?) =
        MmsDecisions.log(context, message, decision, reason, "MMS_CONTENT", ruleId)
}

private fun String.isPreviewable() = isImageMimeType() || isVideoMimeType()
