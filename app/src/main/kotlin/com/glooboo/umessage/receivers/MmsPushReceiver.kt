package com.glooboo.umessage.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SubscriptionManager
import com.glooboo.umessage.spam.IncomingMessage
import android.util.Log
import com.android.mms.transaction.PushReceiver
import org.fossify.commons.helpers.ensureBackgroundThread
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.spam.MmsMedia
import com.glooboo.umessage.spam.mms.BlockedMmsDownloader
import com.glooboo.umessage.spam.mms.IncomingMms
import com.glooboo.umessage.spam.mms.MmsDecisions
import com.glooboo.umessage.spam.mms.MmsPushAction
import com.glooboo.umessage.spam.mms.MmsPushGate
import com.glooboo.umessage.spam.mms.SpamNotice
import com.glooboo.umessage.spam.mms.isRoamingOn
import com.glooboo.umessage.spam.engine.SpamEngine
import java.lang.reflect.Modifier
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * Replaces mmslib's PushReceiver in the manifest. Looks at the WAP push (notification-ind) before anything is
 * downloaded: if the sender is blocked, nothing reaches the system inbox. A placeholder lands in the Blocked folder
 * and the MMS is downloaded into uMessage's private storage only ([BlockedMmsDownloader]), so it can be rescued.
 * An allowed MMS is saved and downloaded by [MmsPushDownload] (the result comes back to the manifest [MmsReceiver]);
 * everything else is handed unchanged to the library's own PushReceiver.
 *
 * Provider truth (6.2, R4, answers 8 and 11): nothing goes to the Blocked folder any more. A notice whose sender's
 * chat is in Spam is saved in the phone's store like any other ([MmsPushDownload] with a [SpamNotice]) and, within
 * the limits, downloaded, because a group MMS cannot be told apart before the download.
 */
class MmsPushReceiver : BroadcastReceiver() {

    /** Saves a let-through notification-ind and starts its download; the result reaches the manifest [MmsReceiver]. */
    internal var pushDownload = MmsPushDownload()

    /** Roaming on the SIM the push came in on (provider truth: a Spam notice is then not downloaded). */
    internal var roaming: (Context, Int) -> Boolean = ::isRoamingOn

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
        var subscriptionId = SubscriptionManager.INVALID_SUBSCRIPTION_ID
        val action = try {
            subscriptionId = intent.receivingSubscriptionId()
            MmsPushGate.decide(
                pushData = intent.getByteArrayExtra(EXTRA_DATA),
                subscriptionId = subscriptionId,
                now = System.currentTimeMillis(),
            ) { probe ->
                // MmsReceiver's content filter runs after download without the SIM; remember it per sender.
                MmsSubscriptions.remember(probe.address, subscriptionId)
                // E-13, provider truth: the push writes no row; the MMS's one row is written where it is placed.
                if (ProviderTruthFlag.enabled) MmsDecisions.probe(context, probe)
                else SpamEngine.evaluate(context, probe, source = "MMS_PUSH")
            }
        } catch (t: Throwable) {
            MmsPushAction.Deliver("gate error")
        }

        var spam: SpamNotice? = null
        if (action is MmsPushAction.Block) {
            if (ProviderTruthFlag.enabled) {
                spam = spamNotice(context, action, subscriptionId)
            } else if (alreadyHandled(action.transactionId) || storeAndDownload(context, action)) {
                return
            }
        }
        // The download result must wake the app even if it is frozen meanwhile (H8): that is [MmsPushDownload].
        // Whatever it does not take (reports, bad data, a failure before the push is saved) goes to the library,
        // which saves and downloads it like any MMS (provider truth: a Spam notice then fails open to Messages).
        if (runCatching { pushDownload.handle(context, intent, spam) }.getOrDefault(false)) return
        delegate(context, intent)
        awaitLibraryPushTask()
    }

    /**
     * Provider truth: the Spam side of a blocked notice. It downloads when within the limits and not roaming, also for
     * a blocked sender (answer 11). Repeats are found in the phone's store by [MmsPushDownload], so a second, different
     * notice from the same sender within seconds is kept as well (M-P5).
     */
    private fun spamNotice(context: Context, action: MmsPushAction.Block, subscriptionId: Int): SpamNotice {
        val notRoaming = !runCatching { roaming(context, subscriptionId) }.getOrDefault(false)
        val notice = SpamNotice(
            sender = action.placeholder.address,
            cause = IncomingMms.causeOf(action.decision),
            downloads = action.withinLimits && notRoaming,
            ruleId = action.decision.ruleId,
        )
        // A notice that is not downloaded now has its final place here: its one decision-log row (E-13).
        if (!notice.downloads) logPush(context, action.placeholder, action)
        return notice
    }

    private fun logPush(context: Context, message: IncomingMessage, action: MmsPushAction.Block) {
        val decision = action.decision
        MmsDecisions.log(context, message, IncomingMms.BLOCK, decision.reason.name, "MMS_PUSH", decision.ruleId)
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
