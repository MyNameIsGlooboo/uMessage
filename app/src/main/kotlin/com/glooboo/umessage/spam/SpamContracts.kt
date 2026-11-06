package com.glooboo.umessage.spam

import kotlinx.coroutines.flow.Flow

/**
 * Public contract of the uMessage spam engine.
 * Frozen interface: implementations live in `spam/engine` and `spam/data`,
 * UI code under the ui package depends only on this file.
 */

enum class UnknownSenderMode { STRICT, SMART, OFF }

/** I03: how a message from an unknown sender that reached the inbox is announced. Codes are never affected. */
enum class UnknownInboxNotify { NOTIFY, SILENT, NONE }

enum class ListKind { ALLOW, BLOCK }

enum class RuleType {
    /** Exact number, compared after normalization. */
    NUMBER,

    /** Normalized number starts with pattern. */
    PREFIX,

    /** Alphanumeric sender id, e.g. "VM-HDFCBK", case-insensitive exact match. */
    SENDER_ID,

    /** Case-insensitive substring of the message body. */
    KEYWORD,

    /** Java regex matched against the message body. */
    REGEX,
}

/** SIM scope of a rule. [subscriptionId] == null means both SIMs. */
data class SpamRule(
    val id: Long = 0,
    val kind: ListKind,
    val type: RuleType,
    val pattern: String,
    val subscriptionId: Int? = null,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

data class IncomingMessage(
    val address: String,
    val body: String,
    val subscriptionId: Int,
    val isMms: Boolean = false,
    val receivedAt: Long = System.currentTimeMillis(),
)

enum class DecisionReason {
    ALLOW_RULE, BLOCK_RULE, CONTACT, OTP_EXEMPTION, UNKNOWN_SENDER, MODE_OFF, NO_MATCH,

    /**
     * Allowed because contacts cannot be read (READ_CONTACTS missing, a provider error, or the sent-message history
     * could not be read), so the filter cannot tell a known number from an unknown one. Unknown-sender blocking is
     * skipped for phone numbers instead of blocking everyone.
     */
    CONTACTS_UNAVAILABLE,

    /** Allowed because the user has sent a message to this number before, so it is known like a contact. */
    KNOWN_CONVERSATION,

    /** I24: held because the sender's number is from outside the home country chosen in the settings. */
    FOREIGN_NUMBER,
}

sealed interface FilterDecision {
    val reason: DecisionReason
    /** [ruleId] names the allow rule that let it through (SE-39); null for every other reason. */
    data class Allow(override val reason: DecisionReason, val ruleId: Long? = null) : FilterDecision
    data class Block(override val reason: DecisionReason, val ruleId: Long? = null) : FilterDecision
}

/** Body stored for an MMS that was blocked before its text could be read (it was never downloaded). */
const val MMS_PLACEHOLDER_BODY = "MMS blocked"

/**
 * What uMessage holds of a blocked MMS's content (pictures, audio, text parts). Always [NONE] for an SMS.
 * Kept content is the whole downloaded MMS (its PDU) in app-private storage, so "Not spam" can put it in the inbox.
 */
enum class MmsMedia {
    /** An SMS. */
    NONE,

    /** uMessage started downloading the MMS when it blocked it; the result has not arrived yet. */
    DOWNLOADING,

    /** The downloaded MMS is kept; "Not spam" restores it with its pictures. */
    STORED,

    /** The MMS content is not kept: the download failed, the carrier expired it, or an older build blocked it. */
    MISSING,
}

/** What a "Not spam" restore of one blocked message did. */
enum class RestoreOutcome {
    /** Everything is in the inbox: an SMS, or an MMS with its pictures. */
    RESTORED,

    /** An MMS whose text is in the inbox (as an SMS), but whose pictures could not be recovered. */
    TEXT_ONLY,

    /** An MMS without text whose content could not be recovered. Nothing was put in the inbox. */
    NOTHING,

    /** The kept MMS could not be put in the inbox. It is still in Blocked; nothing was lost. */
    FAILED,

    /** No such blocked message (already restored or deleted). */
    NOT_FOUND,
}

/**
 * What [SpamRepository.restore] did with one blocked message, kept so [SpamRepository.undoRestore] can take it back.
 * [message] is the blocked row as it was (null when there was none). The other fields are the data layer's own
 * references, 0 when unknown: [inboxId] the system SMS or MMS row it became, [pendingId] the pending_sms row still
 * waiting to be delivered (the inbox insert failed and will be replayed), [eventId] the "restored" statistics event.
 */
data class RestoreReceipt(
    val message: BlockedMessage?,
    val outcome: RestoreOutcome,
    val inboxId: Long = 0,
    val pendingId: Long = 0,
    val eventId: Long = 0,
)

/** A message held back by the filter. Never written to the system SMS provider until restored. */
data class BlockedMessage(
    val id: Long = 0,
    val address: String,
    val body: String,
    val subscriptionId: Int,
    val isMms: Boolean,
    val receivedAt: Long,
    val reason: DecisionReason,
    val ruleId: Long? = null,
    val media: MmsMedia = if (isMms) MmsMedia.MISSING else MmsMedia.NONE,
    /** Name of the kept MMS in app-private storage, set when [media] is [MmsMedia.STORED]. */
    val mediaFile: String? = null,
) {
    /** The message has readable text (an MMS blocked before it was downloaded only has the placeholder). */
    val hasText: Boolean
        get() = body.isNotBlank() && body != MMS_PLACEHOLDER_BODY

    /** The whole MMS, pictures included, is kept and can be restored. */
    val hasStoredMedia: Boolean
        get() = isMms && media == MmsMedia.STORED && !mediaFile.isNullOrBlank()

    /**
     * False for an MMS of which nothing was kept: "Not spam" then puts nothing in the inbox. An MMS whose
     * pictures are lost but whose text is known restores that text (as an SMS).
     */
    val canRestoreContent: Boolean
        get() = !isMms || hasStoredMedia || hasText

    /** What "Not spam" is expected to do with this message (it can still turn out [RestoreOutcome.FAILED]). */
    val expectedRestore: RestoreOutcome
        get() = when {
            !isMms || hasStoredMedia -> RestoreOutcome.RESTORED
            hasText -> RestoreOutcome.TEXT_ONLY
            else -> RestoreOutcome.NOTHING
        }

    /** [media] as the UI should show it: a download that has not answered in [DOWNLOAD_TIMEOUT_MS] has failed. */
    fun mediaAt(now: Long): MmsMedia =
        if (media == MmsMedia.DOWNLOADING && now - receivedAt > DOWNLOAD_TIMEOUT_MS) MmsMedia.MISSING else media

    companion object {
        const val DOWNLOAD_TIMEOUT_MS = 15 * 60 * 1000L
    }
}

data class SpamStats(val blockedTotal: Int, val blockedLast7Days: Int, val restoredTotal: Int)

data class SpamSettings(
    val mode: UnknownSenderMode = UnknownSenderMode.SMART,
    val otpExemption: Boolean = true,
    /** Blocked messages older than this are purged. 0 = keep forever. */
    val purgeAfterDays: Int = 30,
    val notifyOnBlock: Boolean = false,
    val unknownInboxNotify: UnknownInboxNotify = UnknownInboxNotify.NOTIFY,
    /** I67: an "Open link" button on notifications from known senders. */
    val openLinkAction: Boolean = false,
    /** I24: ISO region (e.g. "GB") of the home country; numbers from other countries are held. Blank = off. */
    val blockOutsideRegion: String = "",
)

/** Pure decision logic. No Android I/O except contact lookup supplied by the implementation. */
interface SpamFilter {
    /** Blocking call; run off the main thread. */
    fun evaluate(message: IncomingMessage): FilterDecision
}

interface SpamRepository {
    val blocked: Flow<List<BlockedMessage>>
    val rules: Flow<List<SpamRule>>
    val settings: Flow<SpamSettings>
    val stats: Flow<SpamStats>

    /** Throws IllegalArgumentException for a REGEX rule that [RegexGuard.validate] rejects. */
    suspend fun addRule(rule: SpamRule): Long

    /**
     * Changes the stored rule [SpamRule.id] in place: its id (which blocked messages refer to) and creation time are
     * kept. Returns [SpamRule.id], or the id of another rule that already holds the new value, in which case nothing
     * is changed. Throws IllegalArgumentException for a REGEX rule that [RegexGuard.validate] rejects and
     * NoSuchElementException when the rule no longer exists.
     */
    suspend fun updateRule(rule: SpamRule): Long
    suspend fun removeRule(id: Long)

    /** Removes several rules at once. */
    suspend fun removeRules(ids: List<Long>) = ids.forEach { removeRule(it) }

    /**
     * Undo of a delete: puts [rules] back with their old ids where possible, so the reason chips of blocked messages
     * point at them again. A rule whose value was added again in the meantime is skipped.
     */
    suspend fun restoreRules(rules: List<SpamRule>) = rules.forEach { runCatching { addRule(it.copy(id = 0)) } }
    suspend fun updateSettings(transform: (SpamSettings) -> SpamSettings)

    /** Stores a blocked message (called by receivers). */
    suspend fun storeBlocked(message: BlockedMessage): Long

    /**
     * Inserts the message into the system inbox and shows it. Rules are not changed: the sender is not
     * whitelisted (that is a separate "Always allow" choice). Idempotent: a second call for the same id does
     * nothing ([RestoreOutcome.NOT_FOUND]). A kept MMS goes back with its pictures. Of an MMS whose content was not
     * kept only its text (if any) is restored; the outcome says so, so the UI never claims a picture came back.
     */
    suspend fun markNotSpam(blockedId: Long): RestoreOutcome

    /**
     * [markNotSpam] that can still be undone: the message is in the inbox when this returns, but a kept MMS's files
     * stay until [finishRestore], so [undoRestore] can put it back in Blocked whole. Call [finishRestore] once undo is
     * no longer offered; when the process dies first, the files are swept with the other unreferenced ones.
     */
    suspend fun restore(blockedId: Long): RestoreReceipt = RestoreReceipt(null, markNotSpam(blockedId))

    /**
     * Undo of [restore]: takes the message out of the system inbox and puts it back in Blocked as it was (same id,
     * text, sender, time, SIM, reason and kept files), and takes back the "restored" count. Returns false when it
     * could not be undone; the message then stays in the inbox, so nothing is lost.
     */
    suspend fun undoRestore(receipt: RestoreReceipt): Boolean = false

    /** Ends the undo window of [restore]: the kept MMS files the inbox copy no longer needs are deleted. */
    suspend fun finishRestore(receipt: RestoreReceipt) = Unit

    /** Adds a BLOCK rule for the sender and removes their allow rules. Their blocked messages are kept. */
    suspend fun blockSender(blockedId: Long)
    suspend fun deleteBlocked(ids: List<Long>)
    suspend fun purgeExpired()
}
