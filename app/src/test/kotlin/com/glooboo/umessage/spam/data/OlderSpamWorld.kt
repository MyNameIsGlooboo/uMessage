package com.glooboo.umessage.spam.data

import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.MMS_PLACEHOLDER_BODY
import com.glooboo.umessage.spam.MmsMedia
import com.google.common.truth.Truth.assertThat

/** Thrown by [OlderSpamWorld] when a test "kills the process": from then on nothing the mover does takes effect. */
class Killed : Throwable("killed")

/**
 * A made-up phone for the message migration (section 8): the held rows of spamless.db, the phone's store (SMS and MMS
 * ids counted apart, as in the real store), the caches and the journal, all in memory. [killAfter] "kills the
 * process" right after an operation took effect: that operation and every one after it throws [Killed] and changes
 * nothing, as when the system ends the app. A second mover on the same world is the next start. Every number is made
 * up (from +15550001234).
 *
 * mmslib's write is not atomic here either (F-4, S2-4): it writes the row (dated with the PDU's own date), then one
 * address row after the other, then the time of the write with `date_sent` = the PDU's date; [halfWriteKillAfter]
 * kills after that many address rows.
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
        /** Address rows written so far, and how many the PDU has. */
        var addresses: Int = 1,
        val expectedAddresses: Int = 1,
        var dateSent: Long = 0L,
        /** 132 a downloaded MMS, 130 a "Tap to download" notice. */
        val messageType: Int = RETRIEVE_CONF,
    ) {
        val whole: Boolean get() = addresses >= expectedAddresses
    }

    val blocked = LinkedHashMap<Long, BlockedMessage>()
    val store = mutableListOf<StoreRow>()
    val identities = HashMap<Long, MmsIdentity>()
    val unreadableFiles = HashSet<Long>()
    val missingFiles = HashSet<Long>()
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

    /** Bodies whose write always fails (a message the provider refuses every time). */
    var refusedBodies = emptySet<String>()

    /** The next this many settles fail with the row there. */
    var failingSettles = 0

    /** The next this many repairs throw. */
    var failingRepairs = 0

    /** The next this many journal writes do not reach the disk (S2-6). */
    var failingJournalWrites = 0

    /** The role goes away after this many more writes; null never. */
    var roleLostAfterWrites: Int? = null

    /** mmslib's persist fails; with [leavesPartial] it leaves a whole row it did not report. */
    var persistFails = false
    var leavesPartial = false

    /** The next MMS write stops (a kill) after this many address rows; null: it does not. */
    var halfWriteKillAfter: Int? = null

    /** The next this many journal attempt reads throw (a store that cannot be read during the resume). */
    var failingResumes = 0

    var killAfter: ((op: String) -> Boolean)? = null
    var killed = false

    private var nextSmsId = 1000L
    private var nextMmsId = 1000L
    private var nextRowId = 1L

    // The journal.
    private var attempt: MmsAttempt? = null
    private var lastDoneAt = 0L
    private var halted = -1
    private var pendingRepairs = emptySet<Long>()
    private var passOwed = false
    private var passRequestedAt = 0L
    private var pendingUnread = emptySet<String>()
    private val kinds = HashMap<RowKey, Char>()
    private val stalls = HashMap<RowKey, Int>()
    private var stallsVersion = -1

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

    private fun <T> journalWrite(name: String, block: () -> T): T = op(name) {
        if (failingJournalWrites > 0) {
            failingJournalWrites--
            throw JournalWriteFailed()
        }
        block()
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
        addresses: Int = 2,
    ): BlockedMessage {
        val row = add(
            BlockedMessage(
                address = address, body = body, subscriptionId = sub, isMms = true, receivedAt = at,
                reason = DecisionReason.UNKNOWN_SENDER, media = MmsMedia.STORED, mediaFile = "pdu-${nextRowId}",
            )
        )
        identities[row.id] = MmsIdentity(messageId, body.length, pduDate = at / 1000 - 5, addressCount = addresses)
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
        val stored = row.copy(id = if (row.id > 0) row.id else nextRowId++)
        blocked[stored.id] = stored
        return stored
    }

    /** A message that is in the store already (a downgrade trip, a restore of a backup that has both). */
    fun storeSms(address: String, body: String, at: Long, sub: Int = 1, read: Int = 1): StoreRow =
        StoreRow(nextSmsId++, false, address, body, at, read, read, sub).also { store += it }

    fun storeMms(
        address: String,
        body: String,
        atSeconds: Long,
        messageId: String?,
        messageType: Int = RETRIEVE_CONF,
        dateSent: Long = 0L,
        addresses: Int = 2,
    ): StoreRow = StoreRow(
        nextMmsId++, true, address, body, atSeconds, 1, 1, 1, messageId, addresses = addresses,
        expectedAddresses = addresses, dateSent = dateSent, messageType = messageType,
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

        override fun identity(row: BlockedMessage): KeptPdu = when {
            row.id in unreadableFiles -> KeptPdu.Unreadable
            row.id in missingFiles -> KeptPdu.Missing
            else -> identities[row.id]?.let(KeptPdu::Ready) ?: KeptPdu.Missing
        }

        override fun keptFileExists(row: BlockedMessage): Boolean =
            row.mediaFile != null && row.id !in missingFiles
    }

    private fun received(row: StoreRow) = row.mms && row.inbox && row.messageType == RETRIEVE_CONF

    /** The attempt's own write: the message id; without one the PDU's own date (S2-5). */
    private fun owned(row: StoreRow, identity: MmsIdentity, half: Boolean): Boolean {
        identity.messageId?.let { return row.messageId == it }
        val pduDate = identity.pduDate ?: return false
        return row.date == pduDate || (!half && row.dateSent == pduDate)
    }

    val probe = object : MigrationProbe {
        override fun findSms(address: String, body: String, date: Long): Long? = op("findSms") {
            store.firstOrNull { !it.mms && it.inbox && it.address == address && it.body == body && it.date == date }
                ?.id
        }

        override fun findMms(identity: MmsIdentity, address: String, dateSeconds: Long): Long? = op("findMms") {
            identity.messageId?.let { mid -> store.firstOrNull { received(it) && it.messageId == mid } }?.id
                ?: store.firstOrNull {
                    received(it) && it.whole && it.address == address &&
                        (identity.messageId == null || it.messageId.isNullOrEmpty()) &&
                        (it.date == dateSeconds || (identity.pduDate != null && it.dateSent == identity.pduDate))
                }?.id
        }

        override fun newestMmsId(): Long = op("newestMmsId") { store.filter { it.mms }.maxOfOrNull { it.id } ?: 0L }

        override fun findMmsAfter(attempt: MmsAttempt, address: String, identity: MmsIdentity): Long? =
            op("findMmsAfter") {
                store.firstOrNull {
                    received(it) && it.id > attempt.newestBefore && owned(it, identity, half = false) &&
                        it.addresses >= identity.addressCount && it.address == address
                }?.id
            }

        override fun findHalfWrittenMms(attempt: MmsAttempt, identity: MmsIdentity): Long? = op("findHalf") {
            store.firstOrNull {
                received(it) && it.id > attempt.newestBefore && owned(it, identity, half = true) &&
                    it.addresses < identity.addressCount
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
                val sameId = identity.messageId == null || row?.messageId.isNullOrEmpty() ||
                    row?.messageId == identity.messageId
                val ok = row != null && row.whole && row.address == address && row.date == dateSeconds &&
                    row.inbox && sameId && row.textLength == identity.textLength
                ok && failingChecks-- <= 0
            }
    }

    private fun wrote() {
        roleLostAfterWrites?.let { left ->
            roleLostAfterWrites = left - 1
            if (left - 1 <= 0) isDefault = false
        }
    }

    private fun refusedWrite(subscriptionId: Int, body: String): Boolean {
        if (!isDefault || subscriptionId in rejectedSims || body in refusedBodies) return true
        if (failingWrites > 0) {
            failingWrites--
            return true
        }
        return false
    }

    val writer = object : MigrationWriter {
        override fun canWrite(): Boolean = op("canWrite") { isDefault }

        override fun writeSms(address: String, body: String, date: Long, subscriptionId: Int): Long = op("writeSms") {
            if (refusedWrite(subscriptionId, body)) return@op 0L
            storeSms(address, body, date, subscriptionId).id.also { wrote() }
        }

        override fun writeMms(row: BlockedMessage, subscriptionId: Int): Long = op("writeMms") {
            val identity = identities.getValue(row.id)
            if ((persistFails && !leavesPartial) || refusedWrite(subscriptionId, row.body)) return@op 0L
            // mmslib: the row first, with the PDU's date, then the addresses one by one, then the time of the write.
            val written = StoreRow(
                nextMmsId++, true, row.address, row.body, identity.pduDate ?: 0L, 0, 0, subscriptionId,
                identity.messageId, identity.textLength, addresses = 0, expectedAddresses = identity.addressCount,
            )
            store += written
            repeat(identity.addressCount) {
                if (halfWriteKillAfter == written.addresses) {
                    halfWriteKillAfter = null
                    killed = true
                    throw Killed()
                }
                written.addresses++
            }
            written.date = clock / 1000
            written.dateSent = identity.pduDate ?: 0L
            wrote()
            if (persistFails) 0L else written.id
        }

        override fun deleteMms(id: Long): Boolean = op("deleteMms") { store.removeIf { it.id == id && it.mms } }

        override fun settleMms(id: Long, date: Long): Settle = op("settleMms") {
            val row = store.firstOrNull { it.id == id && it.mms } ?: return@op Settle.GONE
            if (failingSettles > 0) {
                failingSettles--
                return@op Settle.FAILED
            }
            row.date = date / 1000
            row.read = 1
            row.seen = 1
            Settle.DONE
        }

        override fun cache(id: Long, isMms: Boolean): Long? = op("cache") {
            cached += id to isMms
            threadOf(store.first { it.id == id && it.mms == isMms }.address)
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
            set(value) = journalWrite("attempt=") { this@OlderSpamWorld.attempt = value }
        override var lastDoneAt: Long
            get() = op("lastDoneAt?") { this@OlderSpamWorld.lastDoneAt }
            set(value) = journalWrite("lastDoneAt=") { this@OlderSpamWorld.lastDoneAt = value }
        override var haltedVersion: Int
            get() = op("halted?") { halted }
            set(value) = journalWrite("halted=") { halted = value }
        override var pendingRepairs: Set<Long>
            get() = op("repairs?") { this@OlderSpamWorld.pendingRepairs }
            set(value) = journalWrite("repairs=") { this@OlderSpamWorld.pendingRepairs = value }
        override var passOwed: Boolean
            get() = op("owed?") { this@OlderSpamWorld.passOwed }
            set(value) = journalWrite("owed=") { this@OlderSpamWorld.passOwed = value }
        override var passRequestedAt: Long
            get() = op("asked?") { this@OlderSpamWorld.passRequestedAt }
            set(value) = journalWrite("asked=") { this@OlderSpamWorld.passRequestedAt = value }
        override var pendingUnread: Set<String>
            get() = op("unread?") { this@OlderSpamWorld.pendingUnread }
            set(value) = journalWrite("unread=") { this@OlderSpamWorld.pendingUnread = value }
        override var stallsVersion: Int
            get() = this@OlderSpamWorld.stallsVersion
            set(value) = journalWrite("stallsVersion=") { this@OlderSpamWorld.stallsVersion = value }

        override fun kindOf(key: RowKey): Char? = kinds[key]

        override fun setKind(key: RowKey, kind: Char) = journalWrite("kind=") {
            kinds[key] = kind
            Unit
        }

        override fun stallsOf(key: RowKey): Int = stalls[key] ?: 0

        override fun setStalls(key: RowKey, stalls: Int) = journalWrite("stalls=") {
            if (stalls > 0) this@OlderSpamWorld.stalls[key] = stalls else this@OlderSpamWorld.stalls.remove(key)
            Unit
        }

        override fun clearStalls() = journalWrite("stalls-") {
            stalls.clear()
            Unit
        }

        override fun forget(keys: Collection<RowKey>) = journalWrite("forget") {
            keys.forEach {
                kinds.remove(it)
                stalls.remove(it)
            }
            Unit
        }
    }

    fun journalAttempt() = attempt

    fun journalLastDoneAt() = lastDoneAt

    fun journalPendingRepairs() = pendingRepairs

    fun journalPassOwed() = passOwed

    fun journalPendingUnread() = pendingUnread

    fun journalKind(row: BlockedMessage) = kinds[RowKey.of(row)]

    fun journalStalls(row: BlockedMessage) = stalls[RowKey.of(row)] ?: 0

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

/** Every row moved once: its store copy exists exactly once, read, seen and dated when it arrived. */
internal fun OlderSpamWorld.assertAllMovedOnce() {
    for (row in blocked.values) {
        if (!row.isMms || row.hasText || row.media == MmsMedia.STORED) {
            val copies = copiesOf(row)
            assertThat(copies).hasSize(1)
            val copy = copies.single()
            assertThat(copy.read).isEqualTo(1)
            assertThat(copy.seen).isEqualTo(1)
            assertThat(copy.whole).isTrue()
            assertThat(copy.date).isEqualTo(if (copy.mms) row.receivedAt / 1000 else row.receivedAt)
            assertThat(row.migratedMsgId).isEqualTo(copy.id)
        } else {
            assertThat(row.migratedMsgId).isEqualTo(MigrationMark.NOTHING_TO_MOVE)
        }
        assertThat(row.migrateTries).isEqualTo(0)
    }
    assertThat(journalAttempt()).isNull()
    assertThat(journalPendingRepairs()).isEmpty()
}
