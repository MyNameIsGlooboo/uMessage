package com.glooboo.umessage.spam.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.BaseColumns
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.content.edit
import com.glooboo.umessage.BuildConfig
import com.glooboo.umessage.extensions.getConversations
import com.glooboo.umessage.extensions.getMMS
import com.glooboo.umessage.extensions.getThreadId
import com.glooboo.umessage.extensions.insertNewSMS
import com.glooboo.umessage.extensions.insertOrUpdateConversation
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.extensions.recomputeThreadDate
import com.glooboo.umessage.helpers.DefaultSmsRole
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.helpers.refreshConversations
import com.glooboo.umessage.helpers.refreshMessages
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.FirstRun
import com.glooboo.umessage.spam.FirstRunPhase
import com.glooboo.umessage.spam.FirstRunTrigger
import com.glooboo.umessage.spam.MmsMedia
import com.glooboo.umessage.spam.OlderSpamMigration
import com.glooboo.umessage.spam.SpamLogEntry
import com.glooboo.umessage.spam.SpamThreads
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.spam.engine.DefaultSpamFilter
import com.glooboo.umessage.spam.engine.SenderAddress
import com.glooboo.umessage.spam.engine.SenderVerdict
import com.glooboo.umessage.spam.mms.BlockedMmsContent
import com.glooboo.umessage.spam.mms.BlockedMmsStore
import com.google.android.mms.ContentType
import com.google.android.mms.pdu_alt.EncodedStringValue
import com.google.android.mms.pdu_alt.PduHeaders
import com.google.android.mms.pdu_alt.PduParser
import com.google.android.mms.pdu_alt.PduPersister
import com.google.android.mms.pdu_alt.RetrieveConf
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact

/**
 * What the message migration keeps in `blocked_messages.migratedMsgId` (S2, S18). It is a "done" mark and the id the
 * verify step reads; it is never used to find the message later.
 *
 * - null: not moved yet.
 * - [NOTHING_TO_MOVE]: an MMS of which nothing was kept (a placeholder); there is nothing to write.
 * - negative: the message was written to (or found in) the phone's store as row `-mark`, and that row has not passed
 *   the verify yet (a kill came in between, or the verify failed; see `migrateTries`). Such a row is never written
 *   again (S2) and its old copy is never deleted.
 * - positive: the store row of that id passed the verify. The old copy is deleted by the clean-up 30 days later.
 */
internal object MigrationMark {
    const val NOTHING_TO_MOVE = 0L

    fun written(storeId: Long): Long = -storeId

    fun storeIdOf(mark: Long): Long = -mark

    /** Written or found, not verified yet (or the verify failed). */
    fun isWritten(mark: Long?): Boolean = mark != null && mark < 0

    /** Verified, or nothing to move: the old copy may go after the 30 days (S17). */
    fun isDone(mark: Long?): Boolean = mark != null && mark >= 0
}

/**
 * What a kept MMS is in the phone's store: its carrier message id (Mms.MESSAGE_ID; null when it has none), the length
 * of its text as mmslib stores it (F-7: the `text/plain` parts decoded as UTF-8, as mmslib's persist does), and the
 * date its PDU carries (seconds; null when none), which a write that a kill cut off leaves on the row.
 */
internal data class MmsIdentity(val messageId: String?, val textLength: Int, val pduDate: Long? = null)

/** How one held row goes to the phone's store. */
internal sealed interface MoveKind {
    /** An MMS placeholder with nothing kept: marked "nothing to move". */
    data object Nothing : MoveKind

    /** An MMS whose private download may still come in (step E's late result); tried again on a later run. */
    data object Wait : MoveKind

    /** An SMS, or an MMS of which only the text is known (written as an SMS). */
    data class Text(val address: String, val body: String) : MoveKind

    /** An MMS whose downloaded PDU is kept: persisted with mmslib. */
    data class Mms(val address: String, val identity: MmsIdentity) : MoveKind
}

/**
 * An MMS write that started at [startedAt] (wall clock): the newest MMS id of the store just before it, so the row a
 * kill left can be found afterwards.
 */
internal data class MmsAttempt(val rowId: Long, val newestBefore: Long, val startedAt: Long)

/**
 * The end of one run. [waiting]: there is work but uMessage may not write (not the default SMS app). [stalled]: the run
 * stopped at a write or read that failed (F-6); it is tried again later and counts no try.
 */
internal data class MigrationResult(
    val waiting: Boolean = false,
    val moved: Int = 0,
    val failed: Int = 0,
    val halted: Boolean = false,
    /** Rows still to move or to verify after the run (also those that wait for a late MMS download). */
    val left: Int = 0,
    val stalled: Boolean = false,
)

/** The held rows in spamless.db and their kept MMS files. */
internal interface MigrationRows {
    fun all(): List<BlockedMessage>

    fun get(id: Long): BlockedMessage?

    /** Sets migratedMsgId of row [id]; false when the row is gone. */
    fun mark(id: Long, mark: Long): Boolean

    /** One more failed verify of row [id] (migrateTries). */
    fun raiseTries(id: Long)

    /** Deletes the rows; returns the names of the kept MMS files they named. */
    fun delete(ids: List<Long>): List<String>

    fun deleteMedia(names: List<String>)

    /** The identity of the kept PDU of [row]; null when the file is gone or is not a downloaded MMS. */
    fun identity(row: BlockedMessage): MmsIdentity?
}

/** Reads the phone's store. Every call throws when the store cannot be read (no cursor, no permission). */
internal interface MigrationProbe {
    /** S4: an inbox SMS with this sender, text and exact time. */
    fun findSms(address: String, body: String, date: Long): Long?

    /** S4: a received MMS with the carrier message id; without one, with this sender and time (seconds). */
    fun findMms(identity: MmsIdentity, address: String, dateSeconds: Long): Long?

    /** The newest MMS id of the store, 0 when it holds none. */
    fun newestMmsId(): Long

    /**
     * The whole MMS the write [attempt] left (a kill or a failure cut it off before the mark): newer than its newest
     * id, from [address], with the message id of [identity]; without one, dated as mmslib dates a write (the PDU's
     * date, or around [MmsAttempt.startedAt]), so a later MMS of the same sender is not taken for it.
     */
    fun findMmsAfter(attempt: MmsAttempt, address: String, identity: MmsIdentity): Long?

    /**
     * F-4: the half MMS the write [attempt] left when a kill came between mmslib's row and its addresses: newer than
     * its newest id, no address at all, and the message id of [identity] (without one, the PDU's own date, which
     * mmslib writes first). Only uMessage's own write can look like that.
     */
    fun findHalfWrittenMms(attempt: MmsAttempt, identity: MmsIdentity): Long?

    /** The verify of an SMS row: sender, time, text length, received. */
    fun checkSms(id: Long, address: String, date: Long, bodyLength: Int): Boolean

    /** The verify of an MMS row: sender, time (seconds), message id, text length, received. */
    fun checkMms(id: Long, address: String, dateSeconds: Long, identity: MmsIdentity): Boolean
}

/** Writes the phone's store and the app's caches of it. */
internal interface MigrationWriter {
    /** uMessage may write the store now (it holds the default SMS role). */
    fun canWrite(): Boolean

    /** An inbox SMS, read and seen, dated [date], on [subscriptionId]; its id, or 0 when nothing was written. */
    fun writeSms(address: String, body: String, date: Long, subscriptionId: Int): Long

    /** The kept PDU of [row] through mmslib, on [subscriptionId]; its id, or 0 when no whole MMS was written. */
    fun writeMms(row: BlockedMessage, subscriptionId: Int): Long

    /** Deletes MMS [id] (a half write of uMessage's own, F-4); true when it is gone. */
    fun deleteMms(id: Long): Boolean

    /** Date, read and seen of MMS [id] in one update (mmslib wrote the time of the write, unread). */
    fun settleMms(id: Long, date: Long): Boolean

    /** S7: the cache row of the moved message, so search, the digest and the row preview see it. Its thread id. */
    fun cache(id: Long, isMms: Boolean): Long?

    /** After each sender: the thread dated by its newest message and the chat row cached again. */
    fun repair(threadIds: Set<Long>)

    /**
     * A moved message the user had not opened in the old Spam screen: when its sender's chat is in Spam (a one-to-one
     * chat whose sender is judged Spam), its Spam row counts one more unread text. Only after the first sort (F-15).
     */
    fun noteUnread(storeId: Long, isMms: Boolean) = Unit
}

/** What a run keeps across a kill, outside spamless.db. */
@Suppress("TooManyFunctions")
internal interface MigrationJournal {
    /** The MMS write that started and is not settled yet; never replaced while set (F-3). */
    var attempt: MmsAttempt?

    /** Wall-clock time of the last row marked done; the clean-up waits 30 days after it. 0 = never. */
    var lastDoneAt: Long

    /** The app version in which the migration stopped after too many failed verifies; -1 = it did not. */
    var haltedVersion: Int

    /** Threads written to whose date and chat row are not repaired yet; a kill leaves them for the next run. */
    var pendingRepairs: Set<Long>

    /** F-14: rows were moved and their senders' history check was not asked for yet. */
    var passOwed: Boolean

    /** F-15: moved unread rows ("s:<id>" or "m:<id>") whose Spam row counts them once the first sort ran. */
    var pendingUnread: Set<String>

    /** F-22: how row [rowId] was written ([KIND_TEXT] or [KIND_MMS]); null before its first write. */
    fun kindOf(rowId: Long): Char?

    fun setKind(rowId: Long, kind: Char)

    fun forgetKinds(rowIds: Collection<Long>)

    companion object {
        const val KIND_TEXT = 'T'
        const val KIND_MMS = 'M'
    }
}

/**
 * Section 8: moves the messages the 0.4.14 filter held in spamless.db (blocked_messages) into the phone's message
 * store, once. The owner marked this step as very sensitive: a message is never lost and never written twice.
 *
 * Per row, oldest first, in batches of 50 (at most [run]'s limit):
 * 1. A row with a done mark is skipped; it is never written again (S2). The row is read again just before it is
 *    worked on, under the lock "Not spam" takes too (F-1).
 * 2. Probe (S4): SMS by sender, text and exact time; MMS by its carrier message id, else by sender and time. Found:
 *    its id is the mark and the row goes to the verify. A store row another held row already took (a carrier re-send)
 *    makes this row done as well (F-23).
 * 3. Write: an SMS into the inbox, dated when it arrived, read and seen, on its SIM (a SIM this phone does not know
 *    any more: without one); a kept MMS with mmslib the same way; an MMS with text only as an SMS; an MMS placeholder
 *    with nothing kept is marked "nothing to move". How a row was written is kept (F-22).
 * 4. The new id is saved at once as the mark, before the verify (S2). An MMS write is journaled first; while one is
 *    not resolved no other MMS is written (F-3); a kill between mmslib's row and its addresses leaves a half MMS that
 *    is found and deleted before the row is written again (F-4).
 * 5. Before the verify, an MMS uMessage wrote is settled (date, read, seen) again, every time until it worked (F-5).
 *    Verify by that id: sender, time, text length. Only a failed verify raises migrateTries; at most 2 per row; more
 *    than 5 failed rows stop the migration for this app version (decision log). Anything else that fails (the role
 *    lost, a full store, a read that throws) ends the run without a try; it goes on later (F-6).
 * 6. Cache row of the message (S7); after each batch, the threads are dated again and their chat rows cached.
 *
 * A row with SIM id -1, and a row with a blank or placeholder sender (written to the "Unknown" thread), go like any
 * other (S17). A text cut at 10,000 characters is moved cut. The old copies stay; [cleanUp] deletes the done rows
 * and their files 30 days after the last one was done. Every step can be repeated after a kill.
 */
@Suppress("TooManyFunctions")
internal class OlderSpamMover(
    private val rows: MigrationRows,
    private val probe: MigrationProbe,
    private val writer: MigrationWriter,
    private val journal: MigrationJournal,
    private val appVersion: Int,
    private val report: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private enum class Outcome { MOVED, SKIPPED, FAILED_VERIFY, STOP }

    private var moved = 0
    private var failed = 0
    private var notesNow = true
    private var mmsHeld = false
    private val threads = LinkedHashSet<Long>()

    /** Store ids taken by a done row, and by a row written but not verified yet (F-23). */
    private val takenDone = HashSet<Long>()
    private val takenWritten = HashSet<Long>()

    /**
     * One run over at most [limit] rows. [noteUnreadNow] false: the first run's step, before the history check and
     * the first sort; unread counts of moved rows wait in the journal until a later run (F-15).
     */
    fun run(limit: Int = Int.MAX_VALUE, noteUnreadNow: Boolean = true): MigrationResult {
        moved = 0
        failed = 0
        notesNow = noteUnreadNow
        mmsHeld = false
        threads.clear()
        val todo = rows.all().count(::needsWork)
        val unreadWaits = noteUnreadNow && journal.pendingUnread.isNotEmpty()
        return when {
            todo == 0 && journal.pendingRepairs.isEmpty() && !unreadWaits -> MigrationResult(left = 0)
            todo > 0 && journal.haltedVersion == appVersion -> MigrationResult(halted = true, left = todo)
            !writer.canWrite() -> MigrationResult(waiting = todo > 0, left = todo)
            else -> {
                // A kill after the last rows of a batch were done, before their threads were dated again.
                threads += journal.pendingRepairs
                repairTouched()
                if (noteUnreadNow) noteWaitingUnread()
                if (todo == 0) MigrationResult(left = 0) else moveAll(limit)
            }
        }
    }

    /** F-14: whether the moved senders' history check is still owed. */
    fun passOwed(): Boolean = journal.passOwed

    /** F-14: the history check was asked for (or the first run's own pass does it). */
    fun passHandled() {
        journal.passOwed = false
    }

    /**
     * S17: the old copies of rows that were moved (or had nothing to move) are deleted, with their kept files, once
     * 30 days passed since the last row was done. Rows that wait, failed or were not verified always stay.
     */
    fun cleanUp(): Int {
        val last = journal.lastDoneAt
        if (last <= 0L || clock() - last < KEEP_OLD_COPIES_MS) return 0
        val done = rows.all().filter { MigrationMark.isDone(it.migratedMsgId) }.map { it.id }
        if (done.isEmpty()) return 0
        val files = rows.delete(done)
        runCatching { journal.forgetKinds(done) }
        if (files.isNotEmpty()) runCatching { rows.deleteMedia(files) }
        return done.size
    }

    private fun needsWork(row: BlockedMessage): Boolean =
        row.migrateTries < MAX_TRIES && (row.migratedMsgId == null || MigrationMark.isWritten(row.migratedMsgId))

    private fun leftCount(): Int = rows.all().count(::needsWork)

    /** Every row that needs work, oldest first, in batches; stops at a failure that is not a verify (F-6). */
    private fun moveAll(limit: Int): MigrationResult {
        // A cut-off MMS write that could not be resolved holds every MMS of this run back: writing one could double it.
        mmsHeld = runCatching { resumeAttempt() }
            .onFailure { Log.w(TAG, "Older spam: a cut-off MMS write could not be checked") }
            .isFailure || journal.attempt != null
        val all = rows.all()
        noteTaken(all)
        val work = all.filter(::needsWork).sortedWith(compareBy({ it.receivedAt }, { it.id })).take(limit)
        val stalled = moveBatches(work)
        val halted = failed > MAX_FAILED_ROWS
        if (halted) halt()
        val waiting = stalled && !runCatching { writer.canWrite() }.getOrDefault(false)
        return MigrationResult(
            waiting = waiting, moved = moved, failed = failed, halted = halted, left = leftCount(), stalled = stalled,
        )
    }

    /** F-23: the store rows other held rows already took. */
    private fun noteTaken(all: List<BlockedMessage>) {
        takenDone.clear()
        takenWritten.clear()
        all.forEach { row ->
            val mark = row.migratedMsgId ?: return@forEach
            if (mark > 0) takenDone += mark else if (mark < 0) takenWritten += MigrationMark.storeIdOf(mark)
        }
    }

    /**
     * The rows in batches of [BATCH]; true when a row stopped the run (F-6). It stops too after more than
     * [MAX_FAILED_ROWS] failed verifies.
     */
    private fun moveBatches(work: List<BlockedMessage>): Boolean {
        for (batch in work.chunked(BATCH)) {
            for (row in batch) {
                val outcome = moveSafely(row)
                if (outcome == Outcome.FAILED_VERIFY) failed++
                if (outcome == Outcome.STOP || failed > MAX_FAILED_ROWS) {
                    repairTouched()
                    return outcome == Outcome.STOP
                }
            }
            repairTouched()
        }
        return false
    }

    /**
     * F-1: the row is read again (it may have been taken by "Not spam" or deleted). A failure that is not a failed
     * verify (a store that cannot be read, a database error, a write refused) ends the run; no try is counted (F-6).
     */
    private fun moveSafely(row: BlockedMessage): Outcome = runCatching {
        val fresh = rows.get(row.id)?.takeIf(::needsWork) ?: return@runCatching Outcome.SKIPPED
        val kind = kindOf(fresh)
        if (mmsHeld && kind is MoveKind.Mms) Outcome.SKIPPED else move(fresh, kind)
    }.getOrElse {
        Log.w(TAG, "Older spam row not moved: ${it.javaClass.simpleName}")
        Outcome.STOP
    }

    private fun move(row: BlockedMessage, kind: MoveKind): Outcome {
        val mark = row.migratedMsgId
        // Written before (a kill came before the verify, or the verify failed once): verify again, never rewrite.
        if (mark != null && MigrationMark.isWritten(mark)) {
            return verify(row, kind, MigrationMark.storeIdOf(mark), ownWrite = journal.kindOf(row.id) != null)
        }
        return when (kind) {
            MoveKind.Nothing -> done(row.id, MigrationMark.NOTHING_TO_MOVE)
            MoveKind.Wait -> Outcome.SKIPPED
            is MoveKind.Text -> moveText(row, kind)
            is MoveKind.Mms -> moveMms(row, kind)
        }
    }

    /** F-22: a row is read as it was written the first time; a row not written yet by its content. */
    private fun kindOf(row: BlockedMessage): MoveKind {
        val address = targetAddress(row.address)
        return when (journal.kindOf(row.id)) {
            KIND_TEXT -> MoveKind.Text(address, row.body)
            // Its kept file gone after the write: the verify cannot pass, the old copy stays.
            KIND_MMS -> MoveKind.Mms(address, rows.identity(row) ?: MmsIdentity(null, textLength = -1))
            else -> kindByContent(row, address)
        }
    }

    private fun kindByContent(row: BlockedMessage, address: String): MoveKind {
        if (!row.isMms) return MoveKind.Text(address, row.body)
        if (row.hasStoredMedia) rows.identity(row)?.let { return MoveKind.Mms(address, it) }
        val downloading = row.media == MmsMedia.DOWNLOADING && clock() - row.receivedAt < LATE_DOWNLOAD_MS
        return when {
            downloading -> MoveKind.Wait
            row.hasText -> MoveKind.Text(address, row.body)
            else -> MoveKind.Nothing
        }
    }

    /** F-23: a store row another held row took (the same message held twice). */
    private fun takenBy(storeId: Long, row: BlockedMessage): Outcome? = when (storeId) {
        in takenDone -> done(row.id, storeId)
        in takenWritten -> Outcome.SKIPPED
        else -> null
    }

    /** A store row the probe found: taken by another held row (F-23), or this row's, then verified. */
    private fun found(row: BlockedMessage, kind: MoveKind, storeId: Long, ownWrite: Boolean): Outcome =
        takenBy(storeId, row)
            ?: markWritten(row, storeId)?.let { verify(row, kind, storeId, ownWrite) }
            ?: Outcome.SKIPPED

    private fun moveText(row: BlockedMessage, kind: MoveKind.Text): Outcome {
        probe.findSms(kind.address, kind.body, row.receivedAt)?.let { return found(row, kind, it, ownWrite = false) }
        // F-6: the role is checked before every write; lost, the run ends and nothing counts.
        if (!writer.canWrite()) return Outcome.STOP
        journal.setKind(row.id, KIND_TEXT)
        var id = writer.writeSms(kind.address, kind.body, row.receivedAt, simOf(row))
        // A SIM taken out since: newer Android refuses its id. Look first (nothing was written), then without a SIM.
        if (id <= 0L && simOf(row) != DEFAULT_SIM && writer.canWrite()) {
            id = probe.findSms(kind.address, kind.body, row.receivedAt)
                ?: writer.writeSms(kind.address, kind.body, row.receivedAt, DEFAULT_SIM)
        }
        if (id <= 0L) return Outcome.STOP
        return markWritten(row, id)?.let { verify(row, kind, id, ownWrite = true) } ?: Outcome.SKIPPED
    }

    private fun moveMms(row: BlockedMessage, kind: MoveKind.Mms): Outcome {
        if (journal.attempt != null) {
            mmsHeld = true
            return Outcome.SKIPPED
        }
        probe.findMms(kind.identity, kind.address, row.receivedAt / MILLIS)?.let {
            return found(row, kind, it, ownWrite = journal.kindOf(row.id) == KIND_MMS)
        }
        if (!writer.canWrite()) return Outcome.STOP
        journal.setKind(row.id, KIND_MMS)
        val id = writeMmsOnce(row, kind, simOf(row))
            ?: (if (retriesWithoutSim(row)) writeMmsOnce(row, kind, DEFAULT_SIM) else null)
            ?: return Outcome.STOP
        val marked = markWritten(row, id)
        // After the mark the row says which store row is its own; the journal is not needed for it any more.
        journal.attempt = null
        return marked?.let { verify(row, kind, id, ownWrite = true) } ?: Outcome.SKIPPED
    }

    /** As for an SMS: a SIM this phone no longer knows is tried once without one (only when nothing is open). */
    private fun retriesWithoutSim(row: BlockedMessage): Boolean =
        simOf(row) != DEFAULT_SIM && journal.attempt == null && writer.canWrite()

    /**
     * One journaled mmslib write. Returns the whole MMS it left (the journal stays set until the caller marked it), or
     * null with the journal cleared when nothing usable was written; a half write is deleted first (F-4). Throws (and
     * keeps the journal) when the store cannot be read.
     */
    private fun writeMmsOnce(row: BlockedMessage, kind: MoveKind.Mms, subscriptionId: Int): Long? {
        val attempt = MmsAttempt(row.id, probe.newestMmsId(), clock())
        journal.attempt = attempt
        val whole = writer.writeMms(row, subscriptionId).takeIf { it > 0L }
            ?: probe.findMmsAfter(attempt, kind.address, kind.identity)
        if (whole != null) return whole
        probe.findHalfWrittenMms(attempt, kind.identity)?.let { half ->
            check(writer.deleteMms(half)) { "A half written MMS could not be deleted" }
        }
        journal.attempt = null
        return null
    }

    /**
     * A kill during an MMS write: the whole row it left is taken (never written again); a half row is deleted so the
     * row is written again whole. The journal is cleared only when this ended; throws when the store cannot be read.
     */
    private fun resumeAttempt() {
        val attempt = journal.attempt ?: return
        val row = rows.get(attempt.rowId)
        val kind = row?.let(::kindOf) as? MoveKind.Mms
        if (row != null && kind != null && row.migratedMsgId == null) {
            val whole = probe.findMmsAfter(attempt, kind.address, kind.identity)
            if (whole != null) {
                rows.mark(row.id, MigrationMark.written(whole))
            } else {
                probe.findHalfWrittenMms(attempt, kind.identity)?.let { half ->
                    check(writer.deleteMms(half)) { "A half written MMS could not be deleted" }
                }
            }
        }
        journal.attempt = null
    }

    /** Null when the row is gone meanwhile (F-1): the message stays where it is, nothing more is done for it. */
    private fun markWritten(row: BlockedMessage, storeId: Long): Unit? {
        if (!rows.mark(row.id, MigrationMark.written(storeId))) return null
        takenWritten += storeId
        return Unit
    }

    private fun verify(row: BlockedMessage, kind: MoveKind, storeId: Long, ownWrite: Boolean): Outcome {
        // F-5: an MMS uMessage wrote is settled before every verify, until it worked; a settle that fails ends the run.
        if (kind is MoveKind.Mms && ownWrite && !writer.settleMms(storeId, row.receivedAt)) return Outcome.STOP
        val ok = when (kind) {
            is MoveKind.Text -> probe.checkSms(storeId, kind.address, row.receivedAt, kind.body.length)
            is MoveKind.Mms -> probe.checkMms(storeId, kind.address, row.receivedAt / MILLIS, kind.identity)
            MoveKind.Nothing, MoveKind.Wait -> false
        }
        if (!ok) {
            rows.raiseTries(row.id)
            return Outcome.FAILED_VERIFY
        }
        // S7 and the thread's repair before the done mark: a kill in between repeats them, never skips them.
        runCatching { writer.cache(storeId, kind is MoveKind.Mms) }.getOrNull()?.let { threadId ->
            if (threads.add(threadId)) journal.pendingRepairs = threads.toSet()
        }
        done(row.id, storeId)
        takenWritten -= storeId
        takenDone += storeId
        moved++
        // After the done mark: a kill in between loses one unread count at most, it never counts one twice.
        if (row.unread) noteUnread(storeId, kind is MoveKind.Mms)
        return Outcome.MOVED
    }

    private fun noteUnread(storeId: Long, isMms: Boolean) {
        if (notesNow) {
            runCatching { writer.noteUnread(storeId, isMms) }
        } else {
            journal.pendingUnread = journal.pendingUnread + unreadEntry(storeId, isMms)
        }
    }

    /** F-15: the unread counts the first run's step kept for after the sort; each is removed once it was noted. */
    private fun noteWaitingUnread() {
        for (entry in journal.pendingUnread) {
            val isMms = entry.startsWith("m:")
            entry.substringAfter(':').toLongOrNull()?.let { runCatching { writer.noteUnread(it, isMms) } }
            journal.pendingUnread = journal.pendingUnread - entry
        }
    }

    private fun unreadEntry(storeId: Long, isMms: Boolean) = (if (isMms) "m:" else "s:") + storeId

    /** The clean-up's clock first: a kill between the two never leaves a done row that is never cleaned up. */
    private fun done(rowId: Long, mark: Long): Outcome {
        journal.lastDoneAt = clock()
        if (mark > 0L) journal.passOwed = true
        rows.mark(rowId, mark)
        return Outcome.MOVED
    }

    /** F-21: the pending repairs are cleared only by a repair that worked. */
    private fun repairTouched() {
        if (threads.isEmpty()) return
        val repaired = runCatching { writer.repair(threads.toSet()) }
            .onFailure { Log.w(TAG, "Moved chats not repaired", it) }.isSuccess
        threads.clear()
        if (repaired) journal.pendingRepairs = emptySet()
    }

    private fun halt() {
        journal.haltedVersion = appVersion
        Log.w(TAG, "Older spam migration stopped: $failed rows failed")
        runCatching { report(STOPPED) }
    }

    /** S17: a blank or placeholder sender goes to the "Unknown" thread, as the receiver stores such a text. */
    private fun targetAddress(address: String): String =
        if (SenderAddress.isUnreadable(address)) SenderAddress.UNKNOWN_PLACEHOLDER else address

    /** S17: SIM id -1 (or any other unknown value) is the store's default value. */
    private fun simOf(row: BlockedMessage): Int = row.subscriptionId.takeIf { it >= 0 } ?: DEFAULT_SIM

    companion object {
        private const val TAG = "uMessageReceive"
        const val BATCH = 50
        const val MAX_TRIES = 2
        const val MAX_FAILED_ROWS = 5
        const val DEFAULT_SIM = -1
        const val STOPPED = "MIGRATION_STOPPED"
        private const val MILLIS = 1000L
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val KIND_TEXT = MigrationJournal.KIND_TEXT
        private const val KIND_MMS = MigrationJournal.KIND_MMS

        /** S17: old copies of moved rows are kept this long after the last one was done. */
        const val KEEP_OLD_COPIES_MS = 30 * DAY_MS

        /** A held MMS still downloading (step E's late result) waits this long before it is moved by its text. */
        const val LATE_DOWNLOAD_MS = DAY_MS
    }
}

/**
 * The message migration of section 8 on this phone. One run at a time, under [lock], which "Not spam" of the old
 * Blocked screen takes too (F-1); nothing runs while [ProviderTruthFlag] is off. Its callers: the first run's "older
 * spam" step ([firstRunStep], also when uMessage becomes the default app again), and [runPending] at app start, from
 * the daily alarm, the purge, a restore of old Spam entries (S16) and a late private MMS result.
 */
object SpamProviderMigration {
    /** Shared with DefaultSpamRepository's "Not spam" (F-1). */
    internal val lock = Any()

    /** How many rows a run started from a broadcast moves at most; app start moves the rest (F-19). */
    const val RECEIVER_BATCH = 50

    /** Tests replace how the mover is made. */
    @VisibleForTesting
    @Volatile
    internal var moverFor: (Context) -> OlderSpamMover = AndroidOlderSpam::mover

    /** Tests see which pass is asked for. */
    @VisibleForTesting
    @Volatile
    internal var requestPass: (Context, FirstRunTrigger) -> Unit = FirstRun::request

    /**
     * Step 3 of the first run (3.5): true when everything is moved or nothing is left to move; false when it must wait
     * because uMessage is not the default SMS app. A failure (spamless.db cannot be read) throws, so the pass stops
     * and is tried again. Unread counts of moved rows wait for after the sort; the pass's own history check follows,
     * so no pass is owed for it. Set as [FirstRun.olderSpamMigration] at app start.
     */
    val firstRunStep = OlderSpamMigration { context ->
        !migrate(context, noteUnreadNow = false, passFollows = true).waiting
    }

    /** One run; see [OlderSpamMover]. With the flag off nothing happens. Throws when spamless.db cannot be read. */
    internal fun migrate(
        context: Context,
        limit: Int = Int.MAX_VALUE,
        noteUnreadNow: Boolean = true,
        passFollows: Boolean = false,
    ): MigrationResult = migrateOwing(context, limit, noteUnreadNow, passFollows).first

    /** [migrate] and whether the moved senders' history check is still owed (F-14). */
    private fun migrateOwing(
        context: Context,
        limit: Int,
        noteUnreadNow: Boolean,
        passFollows: Boolean,
    ): Pair<MigrationResult, Boolean> {
        if (!ProviderTruthFlag.enabled) return MigrationResult() to false
        val app = context.applicationContext
        return synchronized(lock) {
            val mover = moverFor(app)
            val result = mover.run(limit, noteUnreadNow)
            runCatching { mover.cleanUp() }
            if (passFollows) mover.passHandled()
            result to mover.passOwed()
        }
    }

    /**
     * Moves what still waits (app start, the daily checks, the purge, a restore) and asks for the history check of the
     * moved senders ([FirstRunTrigger.MIGRATION_FINISHED], 3.5) until it was asked for once (F-14); when the pages
     * still say "waits", a [FirstRunTrigger.DEFAULT_AGAIN] pass instead, which clears that. Before the first sort and
     * while a pass works, the pass's own step moves them. [announce] false: the caller asks for a pass of its own.
     * [limit]: at most this many rows (a broadcast, F-19). Never throws; true when nothing waits for the default role.
     */
    fun runPending(context: Context, announce: Boolean = true, limit: Int = Int.MAX_VALUE): Boolean {
        if (!ProviderTruthFlag.enabled) return true
        val state = runCatching { FirstRun.state(context).value }.getOrNull()
        val afterSort = state == null || state.allowsFullSort ||
            (state.sortPublished && state.phase == FirstRunPhase.CLEANUP)
        if (!afterSort) return state?.olderSpamWaiting != true
        val (result, owed) = runCatching { migrateOwing(context, limit, noteUnreadNow = true, passFollows = false) }
            .onFailure { Log.w(TAG, "Older spam migration failed: ${it.javaClass.simpleName}") }
            .getOrNull() ?: return false
        val stale = state?.olderSpamWaiting == true && !result.waiting
        val trigger = when {
            !announce -> null
            stale -> FirstRunTrigger.DEFAULT_AGAIN
            owed -> FirstRunTrigger.MIGRATION_FINISHED
            else -> null
        }
        if (trigger != null) {
            requestPass(context, trigger)
            runCatching { synchronized(lock) { moverFor(context.applicationContext).passHandled() } }
        }
        return !result.waiting
    }

    /**
     * A late result of a held MMS's private download (receivers/BlockedMmsDownloadReceiver, kept one release) was
     * recorded on row [blockedId]. A row marked "nothing to move" meanwhile has content now and is moved again; then
     * a bounded batch of waiting rows is moved. Never throws; nothing with the flag off.
     */
    fun lateMmsResult(context: Context, blockedId: Long) {
        if (!ProviderTruthFlag.enabled) return
        runCatching { AndroidMigrationRows(context.applicationContext).reopenNothingToMove(blockedId) }
        runPending(context, limit = RECEIVER_BATCH)
    }

    /** F-2: the migration stopped after too many failed verifies in this app version (the Older spam page says so). */
    fun stoppedForThisVersion(context: Context): Boolean = PrefsMigrationJournal(
        context.applicationContext.getSharedPreferences(PrefsMigrationJournal.PREFS, Context.MODE_PRIVATE),
    ).haltedVersion == BuildConfig.VERSION_CODE

    /** The decision log line of a stopped migration: no sender, no text, only that it stopped. */
    internal fun reportTo(context: Context): (String) -> Unit = { reason ->
        UMessage.log(context).record(
            SpamLogEntry(System.currentTimeMillis(), "", OlderSpamMover.DEFAULT_SIM, "ERROR", reason, SOURCE)
        )
    }

    private const val TAG = "uMessageReceive"
    private const val SOURCE = "MIGRATION"
}

/** The message migration on the app's own stores: spamless.db, the phone's store, the caches and the journal. */
internal object AndroidOlderSpam {
    fun mover(context: Context): OlderSpamMover {
        val app = context.applicationContext
        val store = AndroidMigrationStore(app)
        return OlderSpamMover(
            rows = AndroidMigrationRows(app),
            probe = store,
            writer = store,
            journal = PrefsMigrationJournal(
                app.getSharedPreferences(PrefsMigrationJournal.PREFS, Context.MODE_PRIVATE),
            ),
            appVersion = BuildConfig.VERSION_CODE,
            report = SpamProviderMigration.reportTo(app),
        )
    }
}

/**
 * The marks are written with plain SQL in a Room transaction (the DAO of step A has no query for them); Room's
 * transaction end tells the screens that read blocked_messages.
 */
internal class AndroidMigrationRows(
    private val app: Context,
    private val db: SpamDatabase = SpamDatabase.getInstance(app),
) : MigrationRows {
    private val dao = db.spamDao()

    override fun all(): List<BlockedMessage> = dao.getAllBlocked().map { it.toModel() }

    override fun get(id: Long): BlockedMessage? = dao.getBlocked(id)?.toModel()

    override fun mark(id: Long, mark: Long): Boolean = db.runInTransaction<Int> {
        val values = ContentValues(1).apply { put(COLUMN_MARK, mark) }
        db.openHelper.writableDatabase.update(TABLE, SQLiteDatabase.CONFLICT_NONE, values, "id = ?", arrayOf(id))
    } > 0

    override fun raiseTries(id: Long) {
        db.runInTransaction {
            db.openHelper.writableDatabase.execSQL(
                "UPDATE `$TABLE` SET `migrateTries` = `migrateTries` + 1 WHERE `id` = ?", arrayOf<Any>(id),
            )
        }
    }

    override fun delete(ids: List<Long>): List<String> = db.runInTransaction<List<String>> {
        ids.chunked(SQL_CHUNK).flatMap { chunk -> dao.mediaFilesOf(chunk).also { dao.deleteBlocked(chunk) } }
    }

    override fun deleteMedia(names: List<String>) = BlockedMmsStore.delete(app, names)

    /** A row marked "nothing to move" whose content came late is not moved yet again. */
    fun reopenNothingToMove(id: Long) {
        db.runInTransaction {
            db.openHelper.writableDatabase.execSQL(
                "UPDATE `$TABLE` SET `$COLUMN_MARK` = NULL WHERE `id` = ? AND `$COLUMN_MARK` = 0", arrayOf<Any>(id),
            )
        }
    }

    override fun identity(row: BlockedMessage): MmsIdentity? {
        val pdu = row.mediaFile?.let { BlockedMmsStore.pduFile(app, it) }?.takeIf { it.isFile }?.readBytes()
            ?: return null
        return MmsIdentities.of(pdu)
    }

    private companion object {
        const val TABLE = "blocked_messages"
        const val COLUMN_MARK = "migratedMsgId"
        const val SQL_CHUNK = 500
    }
}

/** The identity of a kept PDU, read the way mmslib's persist stores it. */
internal object MmsIdentities {
    /** Null when [pdu] is not a downloaded MMS (m-retrieve-conf). */
    fun of(pdu: ByteArray): MmsIdentity? {
        val conf = runCatching { PduParser(pdu, true).parse() as? RetrieveConf }.getOrNull() ?: return null
        val messageId = runCatching { conf.messageId?.let(PduPersister::toIsoString) }.getOrNull()
        return MmsIdentity(
            messageId = messageId?.takeIf { it.isNotBlank() },
            textLength = runCatching { storedTextLength(conf) }.getOrDefault(-1),
            pduDate = runCatching { conf.date }.getOrNull()?.takeIf { it > 0L },
        )
    }

    /**
     * F-7: mmslib's persist keeps a part's text only for the exact type `text/plain` and decodes its bytes as UTF-8,
     * whatever charset the part names; the verify reads that text back. So the expected length is computed the same
     * way: the non-blank `text/plain` parts, one per line.
     */
    fun storedTextLength(conf: RetrieveConf): Int {
        val body = conf.body ?: return 0
        val texts = (0 until body.partsNum).mapNotNull { i ->
            val part = body.getPart(i) ?: return@mapNotNull null
            val type = part.contentType?.let { String(it, Charsets.ISO_8859_1) }
            if (type != ContentType.TEXT_PLAIN) return@mapNotNull null
            EncodedStringValue(part.data ?: ByteArray(0)).string
        }
        return texts.filter { it.isNotBlank() }.joinToString("\n").trim().length
    }
}

/** The phone's store as the migration reads and writes it. Reads throw when the store gives no cursor. */
@Suppress("TooManyFunctions")
internal class AndroidMigrationStore(private val app: Context) : MigrationProbe, MigrationWriter {
    private val readers by lazy { SpamThreads.readers(app) }

    override fun canWrite(): Boolean =
        runCatching { DefaultSmsRole.status(app) == DefaultSmsRole.Status.Held }.getOrDefault(false)

    override fun findSms(address: String, body: String, date: Long): Long? = ids(
        Sms.CONTENT_URI,
        "${Sms.ADDRESS} = ? AND ${Sms.BODY} = ? AND ${Sms.DATE} = ? AND ${Sms.TYPE} = ?",
        arrayOf(address, body, "$date", "${Sms.MESSAGE_TYPE_INBOX}"),
    ).firstOrNull()

    override fun findMms(identity: MmsIdentity, address: String, dateSeconds: Long): Long? {
        identity.messageId?.let { messageId ->
            return ids(Mms.CONTENT_URI, "${Mms.MESSAGE_ID} = ? AND $RECEIVED_MMS", arrayOf(messageId)).firstOrNull()
        }
        return ids(Mms.CONTENT_URI, "${Mms.DATE} = ? AND $RECEIVED_MMS", arrayOf("$dateSeconds"))
            .firstOrNull { sameSender(address, mmsSender(it)) }
    }

    /** F-20: no LIMIT in the sort order; the first row of the newest-first list. */
    override fun newestMmsId(): Long =
        query(Mms.CONTENT_URI, arrayOf(BaseColumns._ID), sortOrder = "${BaseColumns._ID} DESC")
            .use { if (it.moveToFirst()) it.getLong(0) else 0L }

    override fun findMmsAfter(attempt: MmsAttempt, address: String, identity: MmsIdentity): Long? {
        val started = attempt.startedAt / MILLIS
        val mmslibDated = (started - WRITE_SLACK_SECONDS)..(started + WRITE_LATE_SECONDS)
        return newerReceived(attempt).firstOrNull { (id, date, messageId) ->
            val ours = if (identity.messageId != null) {
                messageId == identity.messageId
            } else {
                date == identity.pduDate || date in mmslibDated
            }
            ours && hasAddresses(id) && sameSender(address, mmsSender(id))
        }?.first
    }

    override fun findHalfWrittenMms(attempt: MmsAttempt, identity: MmsIdentity): Long? =
        newerReceived(attempt).firstOrNull { (id, date, messageId) ->
            val ours = if (identity.messageId != null) messageId == identity.messageId else date == identity.pduDate
            ours && !hasAddresses(id)
        }?.first

    /** Received MMS newer than the attempt's newest id: id, date and message id. */
    private fun newerReceived(attempt: MmsAttempt): List<Triple<Long, Long, String?>> = query(
        Mms.CONTENT_URI, arrayOf(BaseColumns._ID, Mms.DATE, Mms.MESSAGE_ID),
        "${BaseColumns._ID} > ? AND $RECEIVED_MMS", arrayOf("${attempt.newestBefore}"), "${BaseColumns._ID} ASC",
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(Triple(c.number(BaseColumns._ID), c.number(Mms.DATE), c.textOrNull(Mms.MESSAGE_ID)))
            }
        }
    }

    override fun checkSms(id: Long, address: String, date: Long, bodyLength: Int): Boolean =
        query(ContentUris.withAppendedId(Sms.CONTENT_URI, id), arrayOf(Sms.ADDRESS, Sms.DATE, Sms.BODY, Sms.TYPE))
            .use { c ->
                c.moveToFirst() && sameSender(address, c.text(Sms.ADDRESS)) && c.number(Sms.DATE) == date &&
                    c.text(Sms.BODY).length == bodyLength && c.number(Sms.TYPE) == Sms.MESSAGE_TYPE_INBOX.toLong()
            }

    override fun checkMms(id: Long, address: String, dateSeconds: Long, identity: MmsIdentity): Boolean {
        val row = query(
            ContentUris.withAppendedId(Mms.CONTENT_URI, id), arrayOf(Mms.DATE, Mms.MESSAGE_BOX, Mms.MESSAGE_ID),
        ).use { c ->
            if (!c.moveToFirst()) return false
            Triple(c.number(Mms.DATE), c.number(Mms.MESSAGE_BOX), c.textOrNull(Mms.MESSAGE_ID))
        }
        val (date, box, messageId) = row
        return date == dateSeconds && box == Mms.MESSAGE_BOX_INBOX.toLong() &&
            (identity.messageId == null || messageId == identity.messageId) &&
            sameSender(address, mmsSender(id)) && mmsTextLength(id) == identity.textLength
    }

    override fun writeSms(address: String, body: String, date: Long, subscriptionId: Int): Long = app.insertNewSMS(
        address = address,
        subject = "",
        body = body,
        date = date,
        read = 1,
        threadId = app.getThreadId(address),
        type = Sms.MESSAGE_TYPE_INBOX,
        subscriptionId = subscriptionId,
        seen = 1,
    )

    override fun writeMms(row: BlockedMessage, subscriptionId: Int): Long =
        RestoredMmsDelivery.persistKept(app, row.copy(subscriptionId = subscriptionId))
            ?.let(ContentUris::parseId) ?: 0L

    override fun deleteMms(id: Long): Boolean =
        app.contentResolver.delete(ContentUris.withAppendedId(Mms.CONTENT_URI, id), null, null) == 1

    override fun settleMms(id: Long, date: Long): Boolean {
        val values = ContentValues().apply {
            put(Mms.DATE, date / MILLIS)
            put(Mms.READ, 1)
            put(Mms.SEEN, 1)
        }
        return app.contentResolver.update(ContentUris.withAppendedId(Mms.CONTENT_URI, id), values, null, null) == 1
    }

    override fun cache(id: Long, isMms: Boolean): Long? {
        val message = message(id, isMms) ?: return null
        app.messagesDB.insertOrUpdate(message)
        return message.threadId
    }

    /** The threads were stamped with the time of the writes: dated by their newest message, their rows cached again. */
    override fun repair(threadIds: Set<Long>) {
        for (threadId in threadIds) {
            app.recomputeThreadDate(threadId)
            app.getConversations(threadId).firstOrNull()?.let { app.insertOrUpdateConversation(it) }
        }
        runCatching { refreshMessages() }
        runCatching { refreshConversations() }
    }

    override fun noteUnread(storeId: Long, isMms: Boolean) {
        val message = message(storeId, isMms) ?: return
        val address = message.senderPhoneNumber
        // Only a one-to-one chat with a readable sender can be in Spam (R4, R7).
        if (SenderAddress.isUnreadable(address) || message.participants.size > 1) return
        val judge = (UMessage.filter(app) as? DefaultSpamFilter)?.senderJudge ?: return
        val spam = judge.verdict(address) as? SenderVerdict.Spam ?: return
        SpamThreads.noteArrival(app, message.threadId, address, spam.cause, System.currentTimeMillis())
    }

    private fun message(id: Long, isMms: Boolean): Message? =
        if (isMms) app.getMMS(ContentUris.withAppendedId(Mms.CONTENT_URI, id)) else smsMessage(id)

    private fun smsMessage(id: Long): Message? {
        val projection = arrayOf(Sms.ADDRESS, Sms.BODY, Sms.DATE, Sms.THREAD_ID, Sms.SUBSCRIPTION_ID)
        return query(ContentUris.withAppendedId(Sms.CONTENT_URI, id), projection).use { c ->
            if (!c.moveToFirst()) return null
            val address = c.text(Sms.ADDRESS)
            val name = app.senderNameFor(address)
            val sender = SimpleContact(
                rawId = 0, contactId = 0, name = name, photoUri = "",
                phoneNumbers = arrayListOf(PhoneNumber(address, 0, "", address)),
                birthdays = ArrayList(), anniversaries = ArrayList(),
            )
            Message(
                id = id, body = c.text(Sms.BODY), type = Sms.MESSAGE_TYPE_INBOX, status = Sms.STATUS_NONE,
                participants = arrayListOf(sender), date = (c.number(Sms.DATE) / MILLIS).toInt(), read = true,
                threadId = c.number(Sms.THREAD_ID), isMMS = false, attachment = null, senderPhoneNumber = address,
                senderName = name, senderPhotoUri = "", subscriptionId = c.number(Sms.SUBSCRIPTION_ID).toInt(),
            )
        }
    }

    /** A blank or placeholder sender ("Unknown") is not compared: such a row has no sender to tell. */
    private fun sameSender(expected: String, stored: String): Boolean =
        SenderAddress.isUnreadable(expected) || expected == stored ||
            runCatching { SpamThreads.same(readers, expected, stored) }.getOrDefault(false)

    private fun mmsSender(id: Long): String = query(
        Uri.parse("${Mms.CONTENT_URI}/$id/addr"), arrayOf(Mms.Addr.ADDRESS),
        "${Mms.Addr.TYPE} = ?", arrayOf("${PduHeaders.FROM}"),
    ).use { if (it.moveToFirst()) it.getString(0).orEmpty() else "" }

    /** mmslib writes the addresses of an MMS last: none means its write was cut off (F-4). */
    private fun hasAddresses(id: Long): Boolean =
        query(Uri.parse("${Mms.CONTENT_URI}/$id/addr"), arrayOf(Mms.Addr.ADDRESS)).use { it.count > 0 }

    /** The text of MMS [id] as mmslib stored it: the non-blank `text/plain` parts, one per line (F-7). */
    private fun mmsTextLength(id: Long): Int = query(
        Uri.parse("${Mms.CONTENT_URI}/part"), arrayOf(Mms.Part.TEXT),
        "${Mms.Part.MSG_ID} = ? AND ${Mms.Part.CONTENT_TYPE} = ?", arrayOf("$id", ContentType.TEXT_PLAIN),
        sortOrder = Mms.Part.SEQ,
    ).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0).orEmpty()) }
    }.filter { it.isNotBlank() }.joinToString("\n").trim().length

    private fun ids(uri: Uri, selection: String?, args: Array<String>?) =
        query(uri, arrayOf(BaseColumns._ID), selection, args, "${BaseColumns._ID} ASC").use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0)) }
        }

    private fun query(
        uri: Uri,
        projection: Array<String>,
        selection: String? = null,
        args: Array<String>? = null,
        sortOrder: String? = null,
    ): Cursor = app.contentResolver.query(uri, projection, selection, args, sortOrder)
        ?: error("The phone's message store gave no answer")

    private fun Cursor.textOrNull(column: String): String? =
        getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }

    private fun Cursor.text(column: String): String = textOrNull(column).orEmpty()

    private fun Cursor.number(column: String): Long =
        getColumnIndexOrThrow(column).let { if (isNull(it)) 0L else getLong(it) }

    private companion object {
        const val MILLIS = 1000L

        /** mmslib dates its write with the clock of the write: a little before the journal's time cannot be it. */
        const val WRITE_SLACK_SECONDS = 60L

        /** ...and a write that took long still started within this of the journal's time. */
        const val WRITE_LATE_SECONDS = 600L

        /** F-16: a downloaded MMS in the inbox (m-retrieve-conf), never a "Tap to download" notice. */
        const val RECEIVED_MMS = "${Mms.MESSAGE_BOX} = ${Mms.MESSAGE_BOX_INBOX} AND " +
            "${Mms.MESSAGE_TYPE} = ${PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF}"
    }
}

/** The journal in its own preferences, written at once: a kill right after must find it. */
@Suppress("TooManyFunctions")
internal class PrefsMigrationJournal(private val prefs: SharedPreferences) : MigrationJournal {
    override var attempt: MmsAttempt?
        get() = if (prefs.contains(KEY_ROW)) {
            MmsAttempt(prefs.getLong(KEY_ROW, 0L), prefs.getLong(KEY_NEWEST, 0L), prefs.getLong(KEY_STARTED, 0L))
        } else {
            null
        }
        set(value) = prefs.edit(commit = true) {
            if (value == null) {
                remove(KEY_ROW)
                remove(KEY_NEWEST)
                remove(KEY_STARTED)
            } else {
                putLong(KEY_ROW, value.rowId)
                putLong(KEY_NEWEST, value.newestBefore)
                putLong(KEY_STARTED, value.startedAt)
            }
        }

    override var lastDoneAt: Long
        get() = prefs.getLong(KEY_DONE_AT, 0L)
        set(value) = prefs.edit(commit = true) { putLong(KEY_DONE_AT, value) }

    override var haltedVersion: Int
        get() = prefs.getInt(KEY_HALTED, -1)
        set(value) = prefs.edit(commit = true) { putInt(KEY_HALTED, value) }

    override var pendingRepairs: Set<Long>
        get() = prefs.getStringSet(KEY_REPAIRS, emptySet()).orEmpty().mapNotNullTo(HashSet()) { it.toLongOrNull() }
        set(value) = prefs.edit(commit = true) { putStringSet(KEY_REPAIRS, value.mapTo(HashSet()) { it.toString() }) }

    override var passOwed: Boolean
        get() = prefs.getBoolean(KEY_PASS_OWED, false)
        set(value) = prefs.edit(commit = true) { putBoolean(KEY_PASS_OWED, value) }

    override var pendingUnread: Set<String>
        get() = prefs.getStringSet(KEY_UNREAD, emptySet()).orEmpty().toSet()
        set(value) = prefs.edit(commit = true) { putStringSet(KEY_UNREAD, HashSet(value)) }

    override fun kindOf(rowId: Long): Char? = kinds()["$rowId"]

    override fun setKind(rowId: Long, kind: Char) = synchronized(LOCK) {
        val next = entries().filterNot { it.substringBefore(':') == "$rowId" } + "$rowId:$kind"
        prefs.edit(commit = true) { putStringSet(KEY_KINDS, HashSet(next)) }
    }

    override fun forgetKinds(rowIds: Collection<Long>) = synchronized(LOCK) {
        val gone = rowIds.mapTo(HashSet()) { "$it" }
        val next = entries().filterNot { it.substringBefore(':') in gone }
        prefs.edit(commit = true) { putStringSet(KEY_KINDS, HashSet(next)) }
    }

    private fun entries(): Set<String> = prefs.getStringSet(KEY_KINDS, emptySet()).orEmpty()

    private fun kinds(): Map<String, Char> = entries().mapNotNull { entry ->
        entry.substringAfter(':', "").singleOrNull()?.let { entry.substringBefore(':') to it }
    }.toMap()

    companion object {
        /** New in provider truth; not a `spamless_` name. Never rename: a killed migration resumes from it. */
        const val PREFS = "umessage_older_spam"
        private val LOCK = Any()
        private const val KEY_ROW = "attempt_row"
        private const val KEY_NEWEST = "attempt_newest_mms"
        private const val KEY_DONE_AT = "last_done_at"
        private const val KEY_HALTED = "halted_version"
        private const val KEY_STARTED = "attempt_started_at"
        private const val KEY_REPAIRS = "pending_repairs"
        private const val KEY_PASS_OWED = "pass_owed"
        private const val KEY_UNREAD = "pending_unread"
        private const val KEY_KINDS = "row_kinds"
    }
}
