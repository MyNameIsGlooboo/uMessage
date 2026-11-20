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
 * mover on the same world is the next start. Every number is made up (the 555 range).
 */
class OlderSpamWorld {

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

    /** mmslib's persist fails; with [leavesPartial] it leaves the row it began. */
    var persistFails = false
    var leavesPartial = false

    var killAfter: ((op: String) -> Boolean)? = null
    var killed = false

    private var nextStoreId = 1000L
    private var nextRowId = 1L

    // The journal.
    private var attempt: MmsAttempt? = null
    private var lastDoneAt = 0L
    private var halted = -1
    private var pendingRepairs = emptySet<Long>()

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

    fun storeMms(address: String, body: String, atSeconds: Long, messageId: String?): StoreRow =
        StoreRow(nextStoreId++, true, address, body, atSeconds, 1, 1, 1, messageId).also { store += it }

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

    val probe = object : MigrationProbe {
        override fun findSms(address: String, body: String, date: Long): Long? = op("findSms") {
            store.firstOrNull { !it.mms && it.inbox && it.address == address && it.body == body && it.date == date }
                ?.id
        }

        override fun findMms(identity: MmsIdentity, address: String, dateSeconds: Long): Long? = op("findMms") {
            if (identity.messageId != null) {
                store.firstOrNull { it.mms && it.inbox && it.messageId == identity.messageId }?.id
            } else {
                store.firstOrNull { it.mms && it.inbox && it.date == dateSeconds && it.address == address }?.id
            }
        }

        override fun newestMmsId(): Long = op("newestMmsId") { store.filter { it.mms }.maxOfOrNull { it.id } ?: 0L }

        override fun findMmsAfter(attempt: MmsAttempt, address: String, identity: MmsIdentity): Long? =
            op("findMmsAfter") {
                val started = attempt.startedAt / 1000
                store.firstOrNull {
                    it.mms && it.inbox && it.id > attempt.newestBefore && it.address == address &&
                        (identity.messageId?.let { id -> it.messageId == id }
                            ?: (it.date == identity.pduDate || it.date in (started - 60)..(started + 600)))
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
                val ok = row != null && row.address == address && row.date == dateSeconds && row.inbox &&
                    (identity.messageId == null || row.messageId == identity.messageId) &&
                    row.textLength == identity.textLength
                ok && failingChecks-- <= 0
            }
    }

    val writer = object : MigrationWriter {
        override fun canWrite(): Boolean = op("canWrite") { isDefault }

        override fun writeSms(address: String, body: String, date: Long, subscriptionId: Int): Long = op("writeSms") {
            if (subscriptionId in rejectedSims) return@op 0L
            storeSms(address, body, date, subscriptionId).id
        }

        override fun writeMms(row: BlockedMessage): Long = op("writeMms") {
            val identity = identities.getValue(row.id)
            if (persistFails && !leavesPartial) return@op 0L
            // mmslib stamps the time of the write, unread and unseen.
            val written = StoreRow(
                nextStoreId++, true, row.address, row.body, clock / 1000, 0, 0, row.subscriptionId,
                identity.messageId, identity.textLength,
            )
            store += written
            if (persistFails) 0L else written.id
        }

        override fun settleMms(id: Long, date: Long): Boolean = op("settleMms") {
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
            repaired += threadIds
            Unit
        }

        override fun noteUnread(storeId: Long, isMms: Boolean, address: String) = op("noteUnread") {
            unreadNoted += storeId
            Unit
        }
    }

    val journal = object : MigrationJournal {
        override var attempt: MmsAttempt?
            get() = op("attempt?") { this@OlderSpamWorld.attempt }
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
    }

    fun journalAttempt() = attempt

    fun journalLastDoneAt() = lastDoneAt

    fun journalPendingRepairs() = pendingRepairs

    companion object {
        const val SENDER = "+15550001234"
        const val NOW = 1_800_000_000_000L
        const val HOUR = 60L * 60 * 1000
        const val DAY = 24 * HOUR

        fun threadOf(address: String): Long = 10L + address.hashCode().toLong().and(0xFFFF)
    }
}
