package com.glooboo.umessage.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
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
import com.glooboo.umessage.extensions.shouldUnarchive
import com.glooboo.umessage.extensions.showReceivedMessageNotification
import com.glooboo.umessage.extensions.updateConversationArchivedStatus
import com.glooboo.umessage.helpers.NotifyContext
import com.glooboo.umessage.helpers.OtpCodeExtractor
import com.glooboo.umessage.helpers.refreshConversations
import com.glooboo.umessage.helpers.showMmsDownloadFailedNotification
import com.glooboo.umessage.helpers.refreshMessages
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.MmsMedia
import com.glooboo.umessage.spam.data.ForwardDelivery
import com.glooboo.umessage.spam.engine.ForwardCandidate
import com.glooboo.umessage.spam.engine.SpamEngine
import com.glooboo.umessage.spam.engine.UnknownNotifyPolicy
import com.glooboo.umessage.spam.mms.BlockedMmsStore
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.data.SpamSettingsStore
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The receiver mmslib sends "MMS downloaded" to (the manifest entry). mmslib's own receiver has a final onReceive
 * that does all its work (persist the MMS, filter it, then call [MmsDownloadHandler.onMessageReceived]) on a bare
 * thread without a PendingResult, so the process could be killed before the notification and the conversation
 * update happen. This wrapper holds the broadcast open with goAsync() until that thread has finished, bounded by
 * [LIBRARY_WORK_TIMEOUT_MS] (well inside the background broadcast limit).
 */
class MmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val handler = MmsDownloadHandler()
        handler.pduPath = intent.getStringExtra(MmsReceivedReceiver.EXTRA_FILE_PATH)
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
                    if (!handler.worker.await(LIBRARY_WORK_TIMEOUT_MS)) {
                        Log.w(TAG, "Downloaded MMS still being handled after $LIBRARY_WORK_TIMEOUT_MS ms")
                    }
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
        if (!started.await(timeoutMs, TimeUnit.MILLISECONDS)) return false
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

    private val pendingAddress = ThreadLocal<String>()

    /** Why the filter let this MMS in; read by [onMessageReceived], which runs on the same worker thread. */
    @Volatile private var allowReason: DecisionReason? = null

    override fun isAddressBlocked(context: Context, address: String?): Boolean {
        worker.mark()
        pendingAddress.set(address.orEmpty().substringBefore("/TYPE="))
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
        val decision = SpamEngine.evaluate(appContext, incoming, source = "MMS_CONTENT")
        val block = decision as? FilterDecision.Block ?: run {
            allowReason = (decision as? FilterDecision.Allow)?.reason
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
        try {
            val mms = if (messageUri != null) {
                context.getMMS(messageUri)
            } else {
                context.getLatestMMS()
            } ?: return

            val address = mms.getSender()?.phoneNumbers?.firstOrNull()?.normalizedNumber ?: mms.senderPhoneNumber
            val size = context.resources.getDimension(R.dimen.notification_large_icon_size).toInt()
            handleMmsMessage(context, mms, size, address)
            // A blocked MMS never gets here (isContentBlocked kept it out of the inbox). Its text is forwarded; the
            // pictures are not (forwards are plain SMS).
            ForwardDelivery.onInboxMessage(
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
        } catch (t: Throwable) {
            Log.e(MmsReceiver.TAG, "Downloaded MMS could not be announced", t)
        }
    }

    override fun onError(context: Context, error: String) {
        worker.mark()
        context.showErrorToast(context.getString(R.string.couldnt_download_mms))
        // The toast is gone before anyone looks: the notification stays (I10).
        runCatching { context.showMmsDownloadFailedNotification() }
    }

    private fun handleMmsMessage(
        context: Context,
        mms: Message,
        size: Int,
        address: String
    ) {
        val glideBitmap = try {
            Glide.with(context)
                .asBitmap()
                .load(mms.attachment!!.attachments.first().getUri())
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
        var reason = allowReason
        if (reason == DecisionReason.MODE_OFF && SpamEngine.hasWrittenTo(context, address)) {
            reason = DecisionReason.KNOWN_CONVERSATION
        }
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

        runCatching {
            context.getConversations(mms.threadId).firstOrNull()?.let { context.insertOrUpdateConversation(it) }
        }
        runCatching {
            if (context.shouldUnarchive()) {
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
