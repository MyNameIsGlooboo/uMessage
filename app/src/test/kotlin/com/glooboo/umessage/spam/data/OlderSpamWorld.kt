package com.glooboo.umessage.spam.data

import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.MMS_PLACEHOLDER_BODY
import com.glooboo.umessage.spam.MmsMedia

/** Thrown by [OlderSpamWorld] when a test "kills the process": from then on nothing the mover does takes effect. */
class Killed : Throwable("killed")

/**
 * A made-up phone for the message migration (section 8): the held rows of spamless.db, the phone's store, the caches
 * and the journal, all in memory. [killAfter] "kills the process" right after an operation took effect: that
 * operation and every one after it throws [Killed] and changes nothing, as when the system ends the app. A second
 * mover on the same world is the next start. Every number is made up (from +15550001234).
 *
 * mmslib's write is not atomic here either (F-4, F-8): it writes the row (dated with the PDU's own date), then its
 * addresses, then the time of the write; [halfWriteKill] kills between the row and its addresses.
 */
internal class OlderSpamWorld {

    /** One row of the phone's store. SMS dates in milliseconds, MMS dates in seconds, as the provider keeps them. */
    data class StoreRow(
        val id: Long,
        val mms: Boolean,
        val address: String,
        val body: String,
        var date: Long,
        var read: Int,
        var seen: Int,
        val sub: Int,
        val messageId: String? = null,
        val textLength: Int = body.length,
        val inbox: Boolean = true,
        var hasAddress: Boolean = true,
        /** 132 a downloaded MMS, 130 a "Tap to download" notice. */
        val messageType: Int = RETRIEVE_CONF,
    )

    val blocked = LinkedHashMap<Long, BlockedMessage>()
    val store = mutableListOf<StoreRow>()
    val identities = HashMap<Long, MmsIdentity>()
    val deletedMedia = mutableListOf<String>()
    val cached = mutableListOf<Pair<Long, Boolean>>()
    val repaired = mutableListOf<Set<Long>>()
    val unreadNoted = mutableListOf<Long>()
    val reports = mutableListOf<String>()
    val trace = mutableListOf<String>()

    var isDefault = true
    var clock = NOW
    var rejectedSims = emptySet<Int>()

    /** The next this many verifies fail. */
    var failingChecks = 0

    /** The next this many writes (SMS or MMS) write nothing (a full store, a provider that refused). */
    var failingWrites = 0

    /** The next this many settles fail. */
    var failingSettles = 0

    /** The next this many repairs throw. */
    var failingRepairs = 0

    /** The role goes away after this many more writes; null never. */
    var roleLostAfterWrites: Int? = null

    /** mmslib's persist fails; with [leavesPartial] it leaves a whole row it did not report. */
    var persistFails = false
    var leavesPartial = false

    /** The next MMS write stops (a kill) after the row, before its addresses. */
    var halfWriteKill = false

    /** The next this many journal attempt reads throw (a store that cannot be read during the resume). */
    var failingResumes = 0

    var killAfter: ((op: String) -> Boolean)? = null
    var killed = false

    private var nextStoreId = 1000L
    private var nextRowId = 1L

    // The journal.
    private var attempt: MmsAttempt? = null
    private var lastDoneAt = 0L
    private var halted = -1
    private var pendingRepairs = emptySet<Long>()
    private var passOwed = false
    private var pendingUnread = emptySet<String>()
    private val kinds = HashMap<Long, Char>()

    private fun <T> op(name: String, block: () -> T): T {
        if (killed) throw Killed()
        val result = block()
        trace += name
        if (killAfter?.invoke(name) == true) {
            killed = true
            throw Killed()
        }
        return result
    }

    /** Ends a kill: the next mover is the next start of the app. */
    fun restart() {
        killed = false
        killAfter = null
    }

    fun sms(
        address: String = SENDER,
        body: String = "Win a prize now",
        at: Long = NOW - HOUR,
        sub: Int = 1,
        unread: Boolean = false,
    ): BlockedMessage = add(
        BlockedMessage(
            address = address, body = body, subscriptionId = sub, isMms = false, receivedAt = at,
            reason = DecisionReason.UNKNOWN_SENDER, unread = unread,
        )
    )

    fun keptMms(
        address: String = SENDER,
        body: String = "Look at this",
        at: Long = NOW - HOUR,
        messageId: String? = "mid-1",
        sub: Int = 1,
    ): BlockedMessage {
        val row = add(
            BlockedMessage(
                address = address, body = body, subscriptionId = sub, isMms = true, receivedAt = at,
                reason = DecisionReason.UNKNOWN_SENDER, media = MmsMedia.STORED, mediaFile = "pdu-${nextRowId}",
            )
        )
        identities[row.id] = MmsIdentity(messageId, body.length, pduDate = at / 1000 - 5)
        return row
    }

    fun placeholderMms(at: Long = NOW - HOUR, media: MmsMedia = MmsMedia.MISSING): BlockedMessage = add(
        BlockedMessage(
            address = SENDER, body = MMS_PLACEHOLDER_BODY, subscriptionId = 1, isMms = true, receivedAt = at,
            reason = DecisionReason.UNKNOWN_SENDER, media = media,
        )
    )

    fun textOnlyMms(body: String = "Only the text", at: Long = NOW - HOUR, media: MmsMedia = MmsMedia.MISSING) = add(
        BlockedMessage(
            address = SENDER, body = body, subscriptionId = 1, isMms = true, receivedAt = at,
            reason = DecisionReason.UNKNOWN_SENDER, media = media,
        )
    )

    fun add(row: BlockedMessage): BlockedMessage {
        val stored = row.copy(id = nextRowId++)
        blocked[stored.id] = stored
        return stored
    }

    /** A message that is in the store already (a downgrade trip, a restore of a backup that has both). */
    fun storeSms(address: String, body: String, at: Long, sub: Int = 1, read: Int = 1): StoreRow =
        StoreRow(nextStoreId++, false, address, body, at, read, read, sub).also { store += it }

    fun storeMms(
        address: String,
        body: String,
        atSeconds: Long,
        messageId: String?,
        messageType: Int = RETRIEVE_CONF,
    ): StoreRow = StoreRow(
        nextStoreId++, true, address, body, atSeconds, 1, 1, 1, messageId, messageType = messageType,
    ).also { store += it }

    fun row(id: Long): BlockedMessage = blocked.getValue(id)

    fun copiesOf(row: BlockedMessage): List<StoreRow> = if (row.isMms && row.media == MmsMedia.STORED) {
        val identity = identities.getValue(row.id)
        store.filter { it.mms && (identity.messageId?.let { id -> it.messageId == id } ?: (it.address == row.address)) }
    } else {
        store.filter { !it.mms && it.body == row.body && it.date == row.receivedAt }
    }

    fun mover(version: Int = 1) = OlderSpamMover(rows, probe, writer, journal, version, { reports += it }) { clock }

    val rows = object : MigrationRows {
        override fun all(): List<BlockedMessage> = op("all") { blocked.values.toList() }

        override fun get(id: Long): BlockedMessage? = op("get") { blocked[id] }

        override fun mark(id: Long, mark: Long): Boolean = op(if (mark > 0L) "markDone" else "markWritten") {
            val row = blocked[id] ?: return@op false
            blocked[id] = row.copy(migratedMsgId = mark)
            true
        }

        override fun raiseTries(id: Long) = op("raiseTries") {
            blocked[id]?.let { blocked[id] = it.copy(migrateTries = it.migrateTries + 1) }
            Unit
        }

        override fun delete(ids: List<Long>): List<String> = op("delete") {
            ids.mapNotNull { blocked.remove(it)?.mediaFile }
        }

        override fun deleteMedia(names: List<String>) = op("deleteMedia") {
            deletedMedia += names
            Unit
        }

        override fun identity(row: BlockedMessage): MmsIdentity? = identities[row.id]
    }

    private fun received(row: StoreRow) = row.mms && row.inbox && row.messageType == RETRIEVE_CONF

    val probe = object : MigrationProbe {
        override fun findSms(address: String, body: String, date: Long): Long? = op("findSms") {
            store.firstOrNull { !it.mms && it.inbox && it.address == address && it.body == body && it.date == date }
                ?.id
        }

        override fun findMms(identity: MmsIdentity, address: String, dateSeconds: Long): Long? = op("findMms") {
            if (identity.messageId != null) {
                store.firstOrNull { received(it) && it.messageId == identity.messageId }?.id
            } else {
                store.firstOrNull {
                    received(it) && it.date == dateSeconds && it.hasAddress && it.address == address
                }?.id
            }
        }

        override fun newestMmsId(): Long = op("newestMmsId") { store.filter { it.mms }.maxOfOrNull { it.id } ?: 0L }

        override fun findMmsAfter(attempt: MmsAttempt, address: String, identity: MmsIdentity): Long? =
            op("findMmsAfter") {
                val started = attempt.startedAt / 1000
                store.firstOrNull {
                    received(it) && it.id > attempt.newestBefore && it.hasAddress && it.address == address &&
                        (identity.messageId?.let { id -> it.messageId == id }
                            ?: (it.date == identity.pduDate || it.date in (started - 60)..(started + 600)))
                }?.id
            }

        override fun findHalfWrittenMms(attempt: MmsAttempt, identity: MmsIdentity): Long? = op("findHalf") {
            store.firstOrNull {
                received(it) && it.id > attempt.newestBefore && !it.hasAddress &&
                    (identity.messageId?.let { id -> it.messageId == id } ?: (it.date == identity.pduDate))
            }?.id
        }

        override fun checkSms(id: Long, address: String, date: Long, bodyLength: Int): Boolean = op("checkSms") {
            val row = store.firstOrNull { it.id == id && !it.mms }
            val ok = row != null && row.address == address && row.date == date && row.body.length == bodyLength &&
                row.inbox
            ok && failingChecks-- <= 0
        }

        override fun checkMms(id: Long, address: String, dateSeconds: Long, identity: MmsIdentity): Boolean =
            op("checkMms") {
                val row = store.firstOrNull { it.id == id && it.mms }
                val ok = row != null && row.hasAddress && row.address == address && row.date == dateSeconds &&
                    row.inbox && (identity.messageId == null || row.messageId == identity.messageId) &&
                    row.textLength == identity.textLength
                ok && failingChecks-- <= 0
            }
    }

    private fun wrote() {
        roleLostAfterWrites?.let { left ->
            roleLostAfterWrites = left - 1
            if (left - 1 <= 0) isDefault = false
        }
    }

    val writer = object : MigrationWriter {
        override fun canWrite(): Boolean = op("canWrite") { isDefault }

        override fun writeSms(address: String, body: String, date: Long, subscriptionId: Int): Long = op("writeSms") {
            if (!isDefault || subscriptionId in rejectedSims) return@op 0L
            if (failingWrites > 0) {
                failingWrites--
                return@op 0L
            }
            storeSms(address, body, date, subscriptionId).id.also { wrote() }
        }

        override fun writeMms(row: BlockedMessage, subscriptionId: Int): Long = op("writeMms") {
            val identity = identities.getValue(row.id)
            val refused = !isDefault || subscriptionId in rejectedSims
            if (refused || (persistFails && !leavesPartial)) return@op 0L
            if (failingWrites > 0) {
                failingWrites--
                return@op 0L
            }
            // mmslib: the row first, with the PDU's date, then the addresses, then the time of the write, unread.
            val written = StoreRow(
                nextStoreId++, true, row.address, row.body, identity.pduDate ?: 0L, 0, 0, subscriptionId,
                identity.messageId, identity.textLength, hasAddress = false,
            )
            store += written
            if (halfWriteKill) {
                halfWriteKill = false
                killed = true
                throw Killed()
            }
            written.hasAddress = true
            written.date = clock / 1000
            wrote()
            if (persistFails) 0L else written.id
        }

        override fun deleteMms(id: Long): Boolean = op("deleteMms") { store.removeIf { it.id == id && it.mms } }

        override fun settleMms(id: Long, date: Long): Boolean = op("settleMms") {
            if (failingSettles > 0) {
                failingSettles--
                return@op false
            }
            store.first { it.id == id }.apply {
                this.date = date / 1000
                read = 1
                seen = 1
            }
            true
        }

        override fun cache(id: Long, isMms: Boolean): Long? = op("cache") {
            cached += id to isMms
            threadOf(store.first { it.id == id }.address)
        }

        override fun repair(threadIds: Set<Long>) = op("repair") {
            if (failingRepairs > 0) {
                failingRepairs--
                error("repair failed")
            }
            repaired += threadIds
            Unit
        }

        override fun noteUnread(storeId: Long, isMms: Boolean) = op("noteUnread") {
            unreadNoted += storeId
            Unit
        }
    }

    val journal = object : MigrationJournal {
        override var attempt: MmsAttempt?
            get() = op("attempt?") {
                if (failingResumes > 0 && this@OlderSpamWorld.attempt != null) {
                    failingResumes--
                    error("store unreadable")
                }
                this@OlderSpamWorld.attempt
            }
            set(value) = op("attempt=") { this@OlderSpamWorld.attempt = value }
        override var lastDoneAt: Long
            get() = op("lastDoneAt?") { this@OlderSpamWorld.lastDoneAt }
            set(value) = op("lastDoneAt=") { this@OlderSpamWorld.lastDoneAt = value }
        override var haltedVersion: Int
            get() = op("halted?") { halted }
            set(value) = op("halted=") { halted = value }
        override var pendingRepairs: Set<Long>
            get() = op("repairs?") { this@OlderSpamWorld.pendingRepairs }
            set(value) = op("repairs=") { this@OlderSpamWorld.pendingRepairs = value }
        override var passOwed: Boolean
            get() = op("owed?") { this@OlderSpamWorld.passOwed }
            set(value) = op("owed=") { this@OlderSpamWorld.passOwed = value }
        override var pendingUnread: Set<String>
            get() = op("unread?") { this@OlderSpamWorld.pendingUnread }
            set(value) = op("unread=") { this@OlderSpamWorld.pendingUnread = value }

        override fun kindOf(rowId: Long): Char? = kinds[rowId]

        override fun setKind(rowId: Long, kind: Char) = op("kind=") {
            kinds[rowId] = kind
            Unit
        }

        override fun forgetKinds(rowIds: Collection<Long>) = op("kinds-") {
            rowIds.forEach { kinds.remove(it) }
            Unit
        }
    }

    fun journalAttempt() = attempt

    fun journalLastDoneAt() = lastDoneAt

    fun journalPendingRepairs() = pendingRepairs

    fun journalPassOwed() = passOwed

    fun journalPendingUnread() = pendingUnread

    fun journalKind(rowId: Long) = kinds[rowId]

    companion object {
        const val SENDER = "+15550001234"
        const val OTHER = "+15550001235"
        const val THIRD = "+15550001236"
        const val NOW = 1_800_000_000_000L
        const val HOUR = 60L * 60 * 1000
        const val DAY = 24 * HOUR
        const val RETRIEVE_CONF = 132
        const val NOTIFICATION_IND = 130

        fun threadOf(address: String): Long = 10L + address.hashCode().toLong().and(0xFFFF)
    }
}
