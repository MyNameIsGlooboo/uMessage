package com.glooboo.umessage.spam.mms

import com.google.android.mms.pdu_alt.GenericPdu
import com.google.android.mms.pdu_alt.NotificationInd
import com.google.android.mms.pdu_alt.PduHeaders
import com.google.android.mms.pdu_alt.PduParser
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.MMS_PLACEHOLDER_BODY

/** What can be learned from a WAP push before anything is downloaded. */
data class MmsPushInfo(
    /** Sender address with any `/TYPE=PLMN` suffix removed. Empty when the PDU carries none. */
    val sender: String,
    val transactionId: String,
    val isNotification: Boolean,
    /** Where the MMS can be downloaded from (X-Mms-Content-Location). Empty when the PDU carries none. */
    val contentLocation: String = "",
    /** When the carrier deletes the MMS, in ms since the epoch; 0 when unknown. */
    val expiresAt: Long = 0,
    /** The size the carrier announces (X-Mms-Message-Size) in bytes; 0 when unknown. */
    val messageSize: Long = 0,
)

sealed interface MmsPushAction {
    /** Hand the push to the library (download into the inbox as before). */
    data class Deliver(val reason: String) : MmsPushAction

    /**
     * Not for the inbox. Store [placeholder] in the Blocked folder and, when [contentLocation] is set, download the
     * MMS into uMessage's private storage so "Not spam" can restore it with its pictures (SE-29).
     */
    data class Block(
        val placeholder: IncomingMessage,
        val decision: FilterDecision.Block,
        val transactionId: String,
        /** Empty when there is nothing to download from (no location, or the carrier has already expired it). */
        val contentLocation: String = "",
    ) : MmsPushAction
}

/** Pure logic of the MMS push gate. No Android I/O, fully JVM-testable. */
object MmsPushGate {
    const val MMS_BLOCKED_BODY = MMS_PLACEHOLDER_BODY

    /** Parses the raw WAP push data. Returns null when it is not a parseable PDU. Never throws. */
    fun parse(pushData: ByteArray?): MmsPushInfo? {
        if (pushData == null || pushData.isEmpty()) return null
        val pdu: GenericPdu = try {
            PduParser(pushData).parse()
        } catch (_: Throwable) {
            null
        } ?: return null
        if (pdu.messageType != PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND || pdu !is NotificationInd) {
            return MmsPushInfo("", "", isNotification = false)
        }
        val from = try {
            pdu.from?.string.orEmpty()
        } catch (_: Throwable) {
            ""
        }
        val transactionId = try {
            pdu.transactionId?.let { String(it, Charsets.ISO_8859_1) }.orEmpty()
        } catch (_: Throwable) {
            ""
        }
        val location = try {
            pdu.contentLocation?.let { String(it, Charsets.ISO_8859_1) }.orEmpty().trim()
        } catch (_: Throwable) {
            ""
        }
        // PduParser turns a relative expiry into an absolute one, in seconds.
        val expiresAt = try {
            pdu.expiry.takeIf { it > 0 }?.let { it * 1000 } ?: 0L
        } catch (_: Throwable) {
            0L
        }
        val size = try {
            pdu.messageSize.coerceAtLeast(0L)
        } catch (_: Throwable) {
            0L
        }
        return MmsPushInfo(cleanAddress(from), transactionId, isNotification = true, location, expiresAt, size)
    }

    fun cleanAddress(raw: String): String = raw.substringBefore("/TYPE=").trim()

    /**
     * Decides what to do with a push. Only notification-ind PDUs with a sender can be blocked;
     * everything else (delivery reports, unparseable data, unknown sender, filter failure) is handed
     * to the library unchanged so that no message is ever lost because of this gate.
     */
    fun decide(
        pushData: ByteArray?,
        subscriptionId: Int,
        now: Long,
        evaluate: (IncomingMessage) -> FilterDecision?,
    ): MmsPushAction {
        val info = parse(pushData) ?: return MmsPushAction.Deliver("unparseable")
        if (!info.isNotification) return MmsPushAction.Deliver("not a notification")
        if (info.sender.isBlank()) return MmsPushAction.Deliver("no sender")
        val probe = IncomingMessage(
            address = info.sender,
            body = "",
            subscriptionId = subscriptionId,
            isMms = true,
            receivedAt = now,
        )
        val decision = evaluate(probe) ?: return MmsPushAction.Deliver("filter failed")
        return when (decision) {
            is FilterDecision.Allow -> MmsPushAction.Deliver(decision.reason.name)
            is FilterDecision.Block -> MmsPushAction.Block(
                placeholder = probe.copy(body = MMS_BLOCKED_BODY),
                decision = decision,
                transactionId = info.transactionId,
                // Expired, or announced larger than BlockedMmsPolicy allows: nothing is downloaded, the row stays.
                contentLocation = info.contentLocation
                    .takeIf { info.expiresAt == 0L || info.expiresAt > now }
                    .takeIf { BlockedMmsPolicy.sizeAllowed(info.messageSize) }
                    .orEmpty(),
            )
        }
    }
}
