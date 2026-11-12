package com.glooboo.umessage.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.android.mms.transaction.PushReceiver
import org.fossify.commons.helpers.ensureBackgroundThread
import com.glooboo.umessage.spam.MmsMedia
import com.glooboo.umessage.spam.mms.BlockedMmsDownloader
import com.glooboo.umessage.spam.mms.MmsPushAction
import com.glooboo.umessage.spam.mms.MmsPushGate
import com.glooboo.umessage.spam.engine.SpamEngine
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * Replaces mmslib's PushReceiver in the manifest. Looks at the WAP push (notification-ind) before anything is
 * downloaded: if the sender is blocked, nothing reaches the system inbox. A placeholder lands in the Blocked folder
 * and the MMS is downloaded into uMessage's private storage only ([BlockedMmsDownloader]), so it can be rescued.
 * Everything else is handed unchanged to the library's own PushReceiver, so allowed MMS behave exactly as before.
 */
class MmsPushReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val appContext = context.applicationContext
        try {
            ensureBackgroundThread {
                try {
                    process(appContext, intent)
                } finally {
                    runCatching { pending.finish() }
                }
            }
        } catch (t: Throwable) {
            // Could not even start the worker: fall back to the stock behaviour right now.
            delegate(appContext, intent)
            runCatching { pending.finish() }
        }
    }

    internal fun process(context: Context, intent: Intent) {
        val action = try {
            val subscriptionId = intent.receivingSubscriptionId()
            MmsPushGate.decide(
                pushData = intent.getByteArrayExtra(EXTRA_DATA),
                subscriptionId = subscriptionId,
                now = System.currentTimeMillis(),
            ) { probe ->
                // MmsReceiver's content filter runs after download without the SIM; remember it per sender.
                MmsSubscriptions.remember(probe.address, subscriptionId)
                SpamEngine.evaluate(context, probe, source = "MMS_PUSH")
            }
        } catch (t: Throwable) {
            MmsPushAction.Deliver("gate error")
        }

        if (action is MmsPushAction.Block) {
            if (alreadyHandled(action.transactionId) || storeAndDownload(context, action)) return
        }
        delegate(context, intent)
        awaitLibraryPushTask()
    }

    /**
     * Stores the placeholder in Blocked and starts downloading the MMS into uMessage's private storage, so "Not spam"
     * can restore it with its pictures later (SE-29). False when even the placeholder could not be stored; the
     * caller then delivers the MMS normally (never lose a message).
     */
    private fun storeAndDownload(context: Context, action: MmsPushAction.Block): Boolean {
        val downloadable = action.contentLocation.isNotEmpty()
        var duplicate = false
        val id = SpamEngine.storeBlockedId(
            context, action.placeholder, action.decision,
            media = if (downloadable) MmsMedia.DOWNLOADING else MmsMedia.MISSING,
            onDuplicate = { duplicate = true },
        ) ?: return false
        // A second push for the same MMS reuses the first row; a second download would overwrite the first one's media.
        if (downloadable && !duplicate) {
            try {
                BlockedMmsDownloader.start(context, id, action.contentLocation, action.placeholder.subscriptionId)
            } catch (t: Throwable) {
                Log.w(MmsReceiver.TAG, "Blocked MMS download did not start", t)
                SpamEngine.attachDownloadedMedia(context, id, null, null)
            }
        }
        return true
    }

    private fun delegate(context: Context, intent: Intent) {
        try {
            PushReceiver().onReceive(context, intent)
        } catch (_: Throwable) {
        }
    }

    /**
     * mmslib's PushReceiver only queues its ReceivePushTask (persist the notification-ind, start the download) on
     * its own single-thread executor and returns, without a PendingResult. Finishing our broadcast right away would
     * let the process be killed before the notification-ind is written, losing the MMS. A no-op queued behind the
     * task on the same executor finishes only after the task has run, so wait for it (bounded, well inside the
     * broadcast timeout) before the caller finishes the broadcast. If the executor can't be found (library change,
     * R8), this returns at once, which is the old behaviour.
     */
    private fun awaitLibraryPushTask() {
        try {
            val executor = libraryExecutor ?: return
            executor.submit {}.get(LIBRARY_TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) {
        }
    }

    /** The carrier re-sends the same notification-ind; store only the first one within a short time. */
    private fun alreadyHandled(transactionId: String): Boolean {
        if (transactionId.isEmpty()) return false
        val now = System.currentTimeMillis()
        seen.entries.removeAll { now - it.value > SEEN_TTL_MS }
        return seen.putIfAbsent(transactionId, now) != null
    }

    private companion object {
        const val EXTRA_DATA = "data"
        const val SEEN_TTL_MS = 10 * 60 * 1000L
        const val LIBRARY_TASK_TIMEOUT_MS = 8_000L
        val seen = ConcurrentHashMap<String, Long>()

        /** PushReceiver's private static PUSH_RECEIVER_EXECUTOR, found by type so field renaming doesn't matter. */
        val libraryExecutor: ExecutorService? by lazy {
            runCatching {
                PushReceiver::class.java.declaredFields
                    .firstOrNull {
                        Modifier.isStatic(it.modifiers) && ExecutorService::class.java.isAssignableFrom(it.type)
                    }
                    ?.apply { isAccessible = true }
                    ?.get(null) as? ExecutorService
            }.getOrNull()
        }
    }
}
