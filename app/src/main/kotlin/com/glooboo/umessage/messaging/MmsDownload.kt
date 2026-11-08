package com.glooboo.umessage.messaging

import com.glooboo.umessage.models.Message
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** What the chat shows for a picture message that was announced but never downloaded (Q40). */
enum class MmsDownloadState { NotDownloaded, Downloading, Failed, Expired }

/**
 * A notification-ind row of the system provider: the carrier announced a picture message that was not downloaded.
 * [expirySeconds] is the carrier's expiry as stored (0 when unknown).
 */
data class PendingMms(
    val id: Long,
    val contentLocation: String,
    val transactionId: String,
    val expirySeconds: Long,
    val subscriptionId: Int,
)

/** The rules behind the "Tap to download" bubble; no Android types, so they are unit tested. */
object MmsDownloadRules {
    /** A download that reported nothing for this long is treated as failed, so the button can be used again. */
    const val DOWNLOAD_TIMEOUT_MS = 120_000L
    private const val MS_PER_SECOND = 1000L

    /**
     * A received MMS with no text and no part is what a notification-ind looks like in the loaded list. Only these
     * are looked up in the provider, so a chat without such rows costs nothing.
     */
    fun isCandidate(message: Message): Boolean =
        message.isMMS && message.isReceivedMessage() && message.body.isBlank() &&
            message.attachment?.attachments.isNullOrEmpty()

    /** True when the carrier's expiry time has passed. An unknown expiry (0) never counts as expired. */
    fun isExpired(expirySeconds: Long, nowMillis: Long): Boolean =
        expirySeconds > 0 && expirySeconds * MS_PER_SECOND < nowMillis

    /**
     * The state to show. An expired message is called "no longer available" only after a download failed: until
     * then the carrier may still serve it, so the button stays.
     */
    fun stateOf(entry: MmsDownloadTracker.Entry?, expirySeconds: Long, nowMillis: Long): MmsDownloadState {
        val failed = entry is MmsDownloadTracker.Entry.Failed ||
            (entry is MmsDownloadTracker.Entry.Downloading && nowMillis - entry.sinceMillis >= DOWNLOAD_TIMEOUT_MS)
        return when {
            failed && isExpired(expirySeconds, nowMillis) -> MmsDownloadState.Expired
            failed -> MmsDownloadState.Failed
            entry is MmsDownloadTracker.Entry.Downloading -> MmsDownloadState.Downloading
            else -> MmsDownloadState.NotDownloaded
        }
    }
}

/**
 * In-memory progress of manual downloads, by the notification-ind row id. Nothing is stored: after a restart a row
 * simply shows "Tap to download" again, and once the download works the row is replaced by the real message.
 */
class MmsDownloadTracker {
    sealed interface Entry {
        data class Downloading(val sinceMillis: Long) : Entry
        data object Failed : Entry
    }

    private val entries = MutableStateFlow<Map<Long, Entry>>(emptyMap())
    val state: StateFlow<Map<Long, Entry>> get() = entries

    /** Marks [id] as downloading. False when a download of it is already running. */
    fun begin(id: Long, nowMillis: Long): Boolean {
        var started = false
        entries.update { current ->
            val running = (current[id] as? Entry.Downloading)
                ?.let { nowMillis - it.sinceMillis < MmsDownloadRules.DOWNLOAD_TIMEOUT_MS } == true
            started = !running
            if (running) current else current + (id to Entry.Downloading(nowMillis))
        }
        return started
    }

    fun fail(id: Long) = entries.update { it + (id to Entry.Failed) }

    /** The system reports a failure without saying which message it was: every running download failed. */
    fun failAllRunning() = entries.update { current ->
        current.mapValues { (_, entry) -> if (entry is Entry.Downloading) Entry.Failed else entry }
    }

    fun forget(id: Long) = entries.update { it - id }

    companion object {
        val shared = MmsDownloadTracker()
    }
}

/** The result of a tap on "Tap to download". */
enum class MmsRetryResult { Started, AlreadyDownloading, NotPending, StartFailed }

/**
 * The tap: looks the message up, marks it downloading and asks the system to download it. [lookup] reads the
 * notification-ind row (null when it is gone, say it was downloaded meanwhile); [start] hands it to the system.
 */
fun retryMmsDownload(
    messageId: Long,
    tracker: MmsDownloadTracker,
    nowMillis: Long,
    lookup: (Long) -> PendingMms?,
    start: (PendingMms) -> Unit,
): MmsRetryResult {
    val pending = lookup(messageId) ?: return MmsRetryResult.NotPending
    if (!tracker.begin(messageId, nowMillis)) return MmsRetryResult.AlreadyDownloading
    return try {
        start(pending)
        MmsRetryResult.Started
    } catch (_: Exception) {
        tracker.fail(messageId)
        MmsRetryResult.StartFailed
    }
}
