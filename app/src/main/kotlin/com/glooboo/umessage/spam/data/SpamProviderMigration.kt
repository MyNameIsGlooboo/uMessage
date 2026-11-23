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
import com.glooboo.umessage.helpers.DefaultSmsRole
import com.glooboo.umessage.helpers.refreshConversations
import com.glooboo.umessage.helpers.refreshMessages
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.FirstRun
import com.glooboo.umessage.spam.FirstRunPhase
import com.glooboo.umessage.spam.FirstRunState
import com.glooboo.umessage.spam.FirstRunTrigger
import com.glooboo.umessage.spam.MmsMedia
import com.glooboo.umessage.spam.OlderSpamMigration
import com.glooboo.umessage.spam.SpamLogEntry
import com.glooboo.umessage.spam.SpamSorting
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
 * of its text as mmslib stores it (F-7: the `text/plain` parts decoded as UTF-8, as mmslib's persist does), the date
 * its PDU carries (seconds; null when none: mmslib writes it as the row's date first and as `date_sent` after its
 * write), and how many address rows mmslib writes for it (S2-4: FROM, TO and CC; a row with fewer is a cut-off write).
 */
internal data class MmsIdentity(
    val messageId: String?,
    val textLength: Int,
    val pduDate: Long? = null,
    val addressCount: Int = 1,
)

/** S2-2: what the kept file of a held MMS gives now. */
internal sealed interface KeptPdu {
    /** No file (or none named): its pictures are gone; the row is moved by its text. */
    data object Missing : KeptPdu

    /** The file is there but cannot be read now: the row is not moved, it is tried again later. */
    data object Unreadable : KeptPdu

    data class Ready(val identity: MmsIdentity) : KeptPdu
}

/** How one held row goes to the phone's store. */
internal sealed interface MoveKind {
    /** An MMS placeholder with nothing kept: marked "nothing to move". */
    data object Nothing : MoveKind

    /** An MMS whose private download may still come in (step E's late result); tried again on a later run. */
    data object Wait : MoveKind

    /** S2-2: an MMS whose kept file cannot be read now: never moved as text, tried again later. */
    data object Unreadable : MoveKind

    /** An SMS, or an MMS of which only the text is known (written as an SMS). */
    data class Text(val address: String, val body: String) : MoveKind

    /** An MMS whose downloaded PDU is kept: persisted with mmslib. */
    data class Mms(val address: String, val identity: MmsIdentity) : MoveKind
}

/**
 * An MMS write that started at [startedAt] (wall clock): the newest MMS id of the store just before it, so the row a
 * kill left can be found afterwards.
 */
internal data class MmsAttempt(val rowId: Long, val newestBefore: Long, val startedAt: Long, val receivedAt: Long)

/** S2-2: a held row as the journal knows it: its id and its arrival, so an id handed out again is another row. */
internal data class RowKey(val id: Long, val receivedAt: Long) {
    companion object {
        fun of(row: BlockedMessage) = RowKey(row.id, row.receivedAt)
    }
}

/** S2-6: a journal write did not reach the disk; the run ends before the store is written. */
internal class JournalWriteFailed : IllegalStateException("The migration journal could not be written")

/**
 * The end of one run. [waiting]: there is work but uMessage may not write (not the default SMS app). [stalled]: the run
 * stopped at a cause for every row (the role, the store, the journal; F-6, S2-3); it goes on later and counts no try.
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

    /** What the kept PDU of [row] gives now (S2-2). */
    fun identity(row: BlockedMessage): KeptPdu

    /** The kept file of [row] is on disk (S2-2: then the clean-up keeps the row unless it went as an MMS). */
    fun keptFileExists(row: BlockedMessage): Boolean

    /** T3-3: a row marked "nothing to move" is not moved yet again (its kept file came late). */
    fun reopen(id: Long)
}

/** Reads the phone's store. Every call throws when the store cannot be read (no cursor, no permission). */
internal interface MigrationProbe {
    /** S4: an inbox SMS with this sender, text and exact time. */
    fun findSms(address: String, body: String, date: Long): Long?

    /**
     * S4: a received MMS with the carrier message id; when there is none with it (S2-1: a restored MMS has no id in the
     * store), with this sender and time (seconds), or this sender and the PDU's own date as `date_sent`.
     */
    fun findMms(identity: MmsIdentity, address: String, dateSeconds: Long): Long?

    /** The newest MMS id of the store, 0 when it holds none. */
    fun newestMmsId(): Long

    /**
     * The whole MMS the write [attempt] left (a kill or a failure cut it off before the mark): newer than its newest
     * id, from [address], with all its address rows, and the message id of [identity]; without one, the PDU's own date
     * as its date or `date_sent` (S2-5), so a later MMS of the same sender is never taken for it.
     */
    fun findMmsAfter(attempt: MmsAttempt, address: String, identity: MmsIdentity): Long?

    /**
     * F-4, S2-4: the half MMS the write [attempt] left when a kill came while mmslib wrote its addresses: newer than
     * its newest id, fewer address rows than the PDU has, and the message id of [identity] (without one, the PDU's
     * own date, which mmslib writes first). Only uMessage's own write can look like that.
     */
    fun findHalfWrittenMms(attempt: MmsAttempt, identity: MmsIdentity): Long?

    /** The carrier message id of MMS [id] in the store; null when it has none (T3-4). */
    fun messageIdOf(id: Long): String?

    /** Whether row [id] can be read back from the store at all (J18: some stores hide a row of a missing SIM). */
    fun visible(id: Long, isMms: Boolean): Boolean = true

    /** The verify of an SMS row: sender, time, text length, received. */
    fun checkSms(id: Long, address: String, date: Long, bodyLength: Int): Boolean

    /** The verify of an MMS row: sender, time (seconds), message id (or none, S2-1), text length, received. */
    fun checkMms(id: Long, address: String, dateSeconds: Long, identity: MmsIdentity): Boolean
}

/** S2-3: how a settle ended. */
internal enum class Settle { DONE, GONE, FAILED }

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

    /** Deletes SMS [id] (a row of uMessage's own that could not be read back, J18); true when it is gone. */
    fun deleteSms(id: Long): Boolean

    /** J18: whether [subscriptionId] is an active SIM now; null when that cannot be told (then it is tried as is). */
    fun simActive(subscriptionId: Int): Boolean? = null

    /** Date, read and seen of MMS [id] in one update; [Settle.GONE] when the row is not there any more. */
    fun settleMms(id: Long, date: Long): Settle

    /** S7: the cache row of the moved message, so search, the digest and the row preview see it. Its thread id. */
    fun cache(id: Long, isMms: Boolean): Long?

    /** The thread of store row [id], read from the store itself (the repair must not depend on the cache row). */
    fun threadOf(id: Long, isMms: Boolean): Long? = null

    /** After each sender: the thread dated by its newest message and the chat row cached again. */
    fun repair(threadIds: Set<Long>)

    /**
     * A moved message the user had not opened in the old Spam screen: when its sender's chat is in Spam (a one-to-one
     * chat whose sender is judged Spam), its Spam row counts one more unread text. Only after the first sort (F-15).
     */
    fun noteUnread(storeId: Long, isMms: Boolean) = Unit
}

/** What a run keeps across a kill, outside spamless.db. Every write throws [JournalWriteFailed] when it failed. */
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

    /** F-14: rows were moved and their senders' history check has not run yet. */
    var passOwed: Boolean

    /** When that history check was last asked for (wall clock); 0 = not yet. */
    var passRequestedAt: Long

    /** F-15: moved unread rows ("s:<id>" or "m:<id>") whose Spam row counts them once the first sort ran. */
    var pendingUnread: Set<String>

    /** F-22, S2-2: how row [key] went to the store ([KIND_TEXT], [KIND_MMS], [KIND_MMS_FOUND]); null before. */
    fun kindOf(key: RowKey): Char?

    fun setKind(key: RowKey, kind: Char)

    /** S2-3: days on which a run stopped at row [key] for a cause of its own. */
    fun stallsOf(key: RowKey): Int

    /** T3-2: the day (wall clock, days since 1970) of the last stall counted for row [key]; -1 when none. */
    fun stallDayOf(key: RowKey): Long

    fun setStalls(key: RowKey, stalls: Int, day: Long)

    /** S2-3: the app version the stall counts belong to; a new version (a fix) tries stuck rows again. */
    var stallsVersion: Int

    fun clearStalls()

    /** Forgets what the journal keeps for rows that are gone. */
    fun forget(keys: Collection<RowKey>)

    companion object {
        const val KIND_TEXT = 'T'

        /** Written by uMessage with mmslib (settled before each verify). */
        const val KIND_MMS = 'M'

        /** Found in the store already (a downgrade trip, a restore): never settled by the migration. */
        const val KIND_MMS_FOUND = 'F'
    }
}

/**
 * Section 8: moves the messages the 0.4.14 filter held in spamless.db (blocked_messages) into the phone's message
 * store, once. The owner marked this step as very sensitive: a message is never lost and never written twice.
 *
 * Per row, oldest first, in batches of 50 (at most [run]'s limit):
 * 1. A row with a done mark is skipped; it is never written again (S2). The row is read again just before it is
 *    worked on, under the lock "Not spam" takes too (F-1).
 * 2. Probe (S4): SMS by sender, text and exact time; MMS by its carrier message id, then by sender and time (S2-1).
 *    Found: its id is the mark and the row goes to the verify. A store row another held row of the same kind already
 *    took (the same message held twice) makes this row done as well (F-23).
 * 3. Write: an SMS into the inbox, dated when it arrived, read and seen, on its SIM (a SIM this phone does not know
 *    any more: without one); a kept MMS with mmslib the same way; an MMS with text only as an SMS; an MMS placeholder
 *    with nothing kept is marked "nothing to move". A kept file that cannot be read now is not moved at all (S2-2).
 *    How a row went is kept once it went (F-22, S2-2).
 * 4. The new id is saved at once as the mark, before the verify (S2). An MMS write is journaled first; while one is
 *    not resolved no other MMS is written (F-3); a kill while mmslib wrote leaves a half MMS (fewer address rows than
 *    its PDU) that is found and deleted before the row is written again (F-4, S2-4).
 * 5. Before the verify, an MMS uMessage wrote is settled (date, read, seen) again, every time until it worked (F-5).
 *    Verify by that id: sender, time, text length. A failed verify (also a settle of a row the user deleted) raises
 *    migrateTries; at most 2 per row; more than 5 failed rows stop the migration for this app version.
 * 6. The role lost, the store or the journal unreadable end the run without a try and count nothing (F-6). A
 *    cause of one row is passed over and counts as a stall of that row, at most once a day; two such rows in a row
 *    (maybe a full store) end the run but count too (T3-1, T3-2). A row that stalled on 3 days is passed over for good
 *    (one decision-log line, "stuck" in Older spam, never cleaned up; S2-3); a new version tries it again.
 * 7. Cache row of the message (S7); after each batch, the threads are dated again and their chat rows cached.
 *
 * A row with SIM id -1, and a row with a blank or placeholder sender (written to the "Unknown" thread), go like any
 * other (S17). A text cut at 10,000 characters is moved cut. The old copies stay; [cleanUp] deletes the done rows
 * and their files 30 days after the last one was done, but never a kept file that did not go as an MMS (S2-2).
 */
@Suppress("TooManyFunctions", "LargeClass")
internal class OlderSpamMover(
    private val rows: MigrationRows,
    private val probe: MigrationProbe,
    private val writer: MigrationWriter,
    private val journal: MigrationJournal,
    private val appVersion: Int,
    private val report: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * [OWN_STALL]: a stall that is the row's own and says nothing about the store (T3-1): its kept file cannot be read,
     * or its only match is a store row another held row took by sender and second (F4-2). It never counts toward "two
     * in a row".
     */
    private enum class Outcome { MOVED, SKIPPED, FAILED_VERIFY, ROW_STALL, OWN_STALL, STOP }

    private var moved = 0
    private var failed = 0
    private var notesNow = true
    private var mmsHeld = false
    private val threads = LinkedHashSet<Long>()

    /** Store rows taken by a done row, and by a row written but not verified yet, as "s:<id>" or "m:<id>" (F-23). */
    private val takenDone = HashSet<String>()
    private val takenWritten = HashSet<String>()

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
        if (journal.stallsVersion != appVersion) {
            journal.clearStalls()
            journal.stallsVersion = appVersion
        }
        val held = rows.all()
        val todo = (if (reopenLatePictures(held)) rows.all() else held).count(::needsWork)
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

    /** When that check was last asked for; 0 = not yet. */
    fun passRequestedAt(): Long = journal.passRequestedAt

    fun passRequested(at: Long) {
        journal.passRequestedAt = at
    }

    /** F-14: the history check ran (the pass that was asked for began after it was asked, or the first run's own). */
    fun passHandled() {
        journal.passOwed = false
    }

    /**
     * S17: the old copies of rows that were moved (or had nothing to move) are deleted, with their kept files, once
     * 30 days passed since the last row was done. Rows that wait, failed or were not verified always stay, and so does
     * a row whose kept file is there but did not go to the store as an MMS (S2-2: its pictures exist only here).
     */
    fun cleanUp(): Int {
        val last = journal.lastDoneAt
        if (last <= 0L || clock() - last < KEEP_OLD_COPIES_MS) return 0
        val done = rows.all().filter { MigrationMark.isDone(it.migratedMsgId) && !keepsPictures(it) }
        if (done.isEmpty()) return 0
        val files = rows.delete(done.map { it.id })
        runCatching { journal.forget(done.map(RowKey::of)) }
        if (files.isNotEmpty()) runCatching { rows.deleteMedia(files) }
        return done.size
    }

    /** S2-2: a kept file on disk whose row did not go as an MMS (done as text, nothing to move, a late result). */
    private fun keepsPictures(row: BlockedMessage): Boolean {
        if (!row.isMms || row.mediaFile.isNullOrBlank() || !rows.keptFileExists(row)) return false
        val asMms = journal.kindOf(RowKey.of(row))?.let { it == KIND_MMS || it == KIND_MMS_FOUND } == true
        return !(asMms && (row.migratedMsgId ?: 0L) > 0L)
    }

    private fun needsWork(row: BlockedMessage): Boolean =
        row.migrateTries < MAX_TRIES && (row.migratedMsgId == null || MigrationMark.isWritten(row.migratedMsgId)) &&
            journal.stallsOf(RowKey.of(row)) < MAX_STALLS

    private fun leftCount(): Int = rows.all().count(::needsWork)

    /** Every row that needs work, oldest first, in batches; stops at a cause for every row (F-6, S2-3). */
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

    /**
     * T3-3: a row marked "nothing to move" whose kept file came later and can be read now is moved as an MMS: nothing
     * was written for it, so no double is possible. A row already done as text keeps its pictures and stays listed.
     */
    private fun reopenLatePictures(held: List<BlockedMessage>): Boolean {
        val late = held.filter { it.migratedMsgId == MigrationMark.NOTHING_TO_MOVE && it.hasStoredMedia }
            .filter { rows.identity(it) is KeptPdu.Ready }
        late.forEach { rows.reopen(it.id) }
        return late.isNotEmpty()
    }

    /** "s:<id>" or "m:<id>": SMS and MMS ids are counted apart in the store (reviewer). */
    private fun takenKey(isMms: Boolean, storeId: Long) = (if (isMms) "m:" else "s:") + storeId

    /** Whether marked row [row] went (or goes) as an MMS: as the journal says, else by its content. */
    private fun goesAsMms(row: BlockedMessage): Boolean =
        journal.kindOf(RowKey.of(row))?.let { it != KIND_TEXT } ?: row.hasStoredMedia

    /** F-23: the store rows other held rows already took. */
    private fun noteTaken(all: List<BlockedMessage>) {
        takenDone.clear()
        takenWritten.clear()
        all.forEach { row ->
            val mark = row.migratedMsgId ?: return@forEach
            when {
                mark > 0 -> takenDone += takenKey(goesAsMms(row), mark)
                mark < 0 -> takenWritten += takenKey(goesAsMms(row), MigrationMark.storeIdOf(mark))
            }
        }
    }

    /**
     * The rows in batches of [BATCH]; true when the run stopped (F-6). A row that failed for a cause of its own is
     * passed over (S2-3). Two such rows in a row may be a full store: the run ends there, but their stalls count all
     * the same (T3-1), so a lasting pair is set aside and the rows after it move; an unreadable kept file is the row's
     * own and never counts toward "two in a row". A stall counts at most once per row per day (T3-2: runs come in
     * bursts), stuck at the third. The role lost or the store unreadable ends the run and counts nothing. It stops too
     * after more than [MAX_FAILED_ROWS] failed verifies.
     */
    private fun moveBatches(work: List<BlockedMessage>): Boolean {
        val stalls = mutableListOf<BlockedMessage>()
        var lastStalled = false
        for ((index, row) in work.withIndex()) {
            if (index > 0 && index % BATCH == 0) repairTouched()
            val outcome = moveSafely(row)
            if (outcome == Outcome.FAILED_VERIFY) failed++
            val pair = outcome == Outcome.ROW_STALL && lastStalled
            if (outcome == Outcome.ROW_STALL || outcome == Outcome.OWN_STALL) stalls += row
            if (outcome != Outcome.SKIPPED && outcome != Outcome.OWN_STALL) lastStalled = outcome == Outcome.ROW_STALL
            if (outcome == Outcome.STOP || pair || failed > MAX_FAILED_ROWS) {
                return endRun(stalls, counted = outcome != Outcome.STOP, stopped = outcome == Outcome.STOP || pair)
            }
        }
        return endRun(stalls, counted = true, stopped = false)
    }

    /** The last batch's threads are repaired; the stalls count unless the role or the store stopped the run. */
    private fun endRun(stalls: MutableList<BlockedMessage>, counted: Boolean, stopped: Boolean): Boolean {
        repairTouched()
        if (counted) countStalls(stalls)
        return stopped
    }

    /** S2-3, T3-2: the rows passed over in this run, once per row per day; a third stall makes the row stuck. */
    private fun countStalls(stalls: MutableList<BlockedMessage>) {
        val today = clock() / DAY_MS
        for (row in stalls) {
            val key = RowKey.of(row)
            if (journal.stallDayOf(key) == today) continue
            val count = journal.stallsOf(key) + 1
            journal.setStalls(key, count, today)
            if (count == MAX_STALLS) {
                Log.w(TAG, "Older spam: one row stuck after $MAX_STALLS days")
                runCatching { report(ROW_STUCK) }
            }
        }
        stalls.clear()
    }

    /**
     * F-1: the row is read again (it may have been taken by "Not spam" or deleted). A failure is a cause of this row
     * unless the role, the store or the journal is gone (F-6, S2-3); no try is counted for it.
     */
    private fun moveSafely(row: BlockedMessage): Outcome = runCatching {
        val fresh = rows.get(row.id)?.takeIf(::needsWork) ?: return@runCatching Outcome.SKIPPED
        val kind = kindOf(fresh)
        when {
            kind == MoveKind.Unreadable -> Outcome.OWN_STALL
            mmsHeld && kind is MoveKind.Mms -> Outcome.SKIPPED
            else -> move(fresh, kind)
        }
    }.getOrElse { error ->
        if (error is JournalWriteFailed) return Outcome.STOP
        Log.w(TAG, "Older spam row not moved: ${error.javaClass.simpleName}")
        rowFailure()
    }

    /** S2-3: a failure with the role held and the store answering belongs to the row; else to the run. */
    private fun rowFailure(): Outcome {
        val runWide = !runCatching { writer.canWrite() }.getOrDefault(false) ||
            runCatching { probe.newestMmsId() }.isFailure
        return if (runWide) Outcome.STOP else Outcome.ROW_STALL
    }

    private fun move(row: BlockedMessage, kind: MoveKind): Outcome {
        val mark = row.migratedMsgId
        // Written before (a kill came before the verify, or the verify failed once): verify again, never rewrite.
        if (mark != null && MigrationMark.isWritten(mark)) {
            val own = journal.kindOf(RowKey.of(row)) == KIND_MMS
            return verify(row, kind, MigrationMark.storeIdOf(mark), ownWrite = own)
        }
        return when (kind) {
            MoveKind.Nothing -> done(row, MigrationMark.NOTHING_TO_MOVE)
            MoveKind.Wait -> Outcome.SKIPPED
            MoveKind.Unreadable -> Outcome.OWN_STALL
            is MoveKind.Text -> moveText(row, kind)
            is MoveKind.Mms -> moveMms(row, kind)
        }
    }

    /** F-22, S2-2: a row is read as it went the first time; a row not gone yet by its content. */
    private fun kindOf(row: BlockedMessage): MoveKind {
        val address = targetAddress(row.address)
        return when (journal.kindOf(RowKey.of(row))) {
            KIND_TEXT -> MoveKind.Text(address, row.body)
            KIND_MMS, KIND_MMS_FOUND -> when (val kept = rows.identity(row)) {
                is KeptPdu.Ready -> MoveKind.Mms(address, kept.identity)
                KeptPdu.Unreadable -> MoveKind.Unreadable
                // Its kept file gone after the write: the verify cannot pass, the old copy stays.
                KeptPdu.Missing -> MoveKind.Mms(address, MmsIdentity(null, textLength = -1))
            }
            else -> kindByContent(row, address)
        }
    }

    private fun kindByContent(row: BlockedMessage, address: String): MoveKind {
        if (!row.isMms) return MoveKind.Text(address, row.body)
        if (row.hasStoredMedia) {
            when (val kept = rows.identity(row)) {
                is KeptPdu.Ready -> return MoveKind.Mms(address, kept.identity)
                KeptPdu.Unreadable -> return MoveKind.Unreadable
                KeptPdu.Missing -> Unit
            }
        }
        val downloading = row.media == MmsMedia.DOWNLOADING && clock() - row.receivedAt < LATE_DOWNLOAD_MS
        return when {
            downloading -> MoveKind.Wait
            row.hasText -> MoveKind.Text(address, row.body)
            else -> MoveKind.Nothing
        }
    }

    /**
     * F-23: a store row another held row of the same kind took (the same message held twice). T3-4: such a row is
     * done at once only when the match is certain (an SMS: sender, text, exact time; an MMS: its carrier id); a match
     * by sender and second only may be another MMS, so the row is left unmoved and listed (no kind, never cleaned),
     * and it counts as a stall of the row: stuck after three days (F4-2).
     */
    private fun takenBy(kind: MoveKind, storeId: Long, row: BlockedMessage): Outcome? {
        val isMms = kind is MoveKind.Mms
        val key = takenKey(isMms, storeId)
        if (key !in takenDone && key !in takenWritten) return null
        val certain = kind !is MoveKind.Mms ||
            (kind.identity.messageId != null && probe.messageIdOf(storeId) == kind.identity.messageId)
        return when {
            !certain -> Outcome.OWN_STALL
            key in takenDone -> done(row, storeId, if (isMms) KIND_MMS_FOUND else KIND_TEXT)
            else -> Outcome.SKIPPED
        }
    }

    /** A store row the probe found: taken by another held row (F-23), or this row's, then verified. */
    private fun found(row: BlockedMessage, kind: MoveKind, storeId: Long): Outcome {
        val isMms = kind is MoveKind.Mms
        return takenBy(kind, storeId, row)
            ?: markWritten(row, storeId, if (isMms) KIND_MMS_FOUND else KIND_TEXT)
                ?.let { verify(row, kind, storeId, ownWrite = false) }
            ?: Outcome.SKIPPED
    }

    private fun moveText(row: BlockedMessage, kind: MoveKind.Text): Outcome {
        probe.findSms(kind.address, kind.body, row.receivedAt)?.let { return found(row, kind, it) }
        // F-6: the role is checked before every write; lost, the run ends and nothing counts.
        if (!writer.canWrite()) return Outcome.STOP
        val sim = simToWrite(row)
        var id = writer.writeSms(kind.address, kind.body, row.receivedAt, sim)
        // A SIM taken out since: newer Android refuses its id. Look first (nothing was written), then without a SIM.
        if (id <= 0L && sim != DEFAULT_SIM && writer.canWrite()) {
            id = probe.findSms(kind.address, kind.body, row.receivedAt)
                ?: writer.writeSms(kind.address, kind.body, row.receivedAt, DEFAULT_SIM)
        } else if (id > 0L && sim != DEFAULT_SIM && !probe.visible(id, isMms = false)) {
            val again = rewriteHiddenSms(row, kind, id)
            id = again.first
            again.second?.let { return it }
        }
        if (id <= 0L) return rowFailure()
        return markWritten(row, id, KIND_TEXT)?.let { verify(row, kind, id, ownWrite = true) } ?: Outcome.SKIPPED
    }

    private fun moveMms(row: BlockedMessage, kind: MoveKind.Mms): Outcome {
        if (journal.attempt != null) {
            mmsHeld = true
            return Outcome.SKIPPED
        }
        probe.findMms(kind.identity, kind.address, row.receivedAt / MILLIS)?.let { return found(row, kind, it) }
        if (!writer.canWrite()) return Outcome.STOP
        val sim = simToWrite(row)
        var id = writeMmsOnce(row, kind, sim)
            ?: (if (sim != DEFAULT_SIM && retriesWithoutSim(row)) writeMmsOnce(row, kind, DEFAULT_SIM) else null)
            ?: return if (journal.attempt != null) Outcome.STOP else rowFailure()
        if (sim != DEFAULT_SIM && !probe.visible(id, isMms = true)) {
            val again = rewriteHiddenMms(row, kind, id)
            id = again.first
            again.second?.let { return it }
        }
        val marked = markWritten(row, id, KIND_MMS)
        // After the mark the row says which store row is its own; the journal is not needed for it any more.
        journal.attempt = null
        return marked?.let { verify(row, kind, id, ownWrite = true) } ?: Outcome.SKIPPED
    }

    /**
     * J18: the store took SMS [hidden] (written with a SIM) but hides it: it is deleted and written again without a
     * SIM. The new id, or the outcome that ends the row; a row still hidden is deleted again, none is left behind.
     */
    private fun rewriteHiddenSms(row: BlockedMessage, kind: MoveKind.Text, hidden: Long): Pair<Long, Outcome?> {
        if (!runCatching { writer.deleteSms(hidden) }.getOrDefault(false)) return 0L to hiddenLeft()
        if (!writer.canWrite()) return 0L to Outcome.STOP
        val id = writer.writeSms(kind.address, kind.body, row.receivedAt, DEFAULT_SIM)
        if (id > 0L && !probe.visible(id, isMms = false)) {
            runCatching { writer.deleteSms(id) }
            return 0L to failedVerify(row)
        }
        return id to null
    }

    /** J18: as [rewriteHiddenSms] for an MMS: mmslib's delete takes its parts and addresses with it. */
    private fun rewriteHiddenMms(row: BlockedMessage, kind: MoveKind.Mms, hidden: Long): Pair<Long, Outcome?> {
        if (!deleteHiddenMms(hidden)) return 0L to hiddenLeft()
        if (!writer.canWrite()) return 0L to Outcome.STOP
        val id = writeMmsOnce(row, kind, DEFAULT_SIM)
            ?: return 0L to (if (journal.attempt != null) Outcome.STOP else rowFailure())
        if (!probe.visible(id, isMms = true)) {
            deleteHiddenMms(id)
            return 0L to failedVerify(row)
        }
        return id to null
    }

    /** J18: a hidden MMS of our own write is deleted (its parts and addresses go with it); the journal is cleared. */
    private fun deleteHiddenMms(id: Long): Boolean {
        val gone = runCatching { writer.deleteMms(id) }.getOrDefault(false)
        if (gone) journal.attempt = null
        return gone
    }

    /** J18: a hidden row that could not be deleted: a failed verify of the row; the journal does not hold the run. */
    private fun hiddenLeft(): Outcome {
        Log.w(TAG, "Older spam: a row the store hides could not be deleted")
        journal.attempt = null
        return Outcome.FAILED_VERIFY
    }

    /** J18: the row's SIM, or none when it is not among the active SIMs now (when that cannot be told, as it is). */
    private fun simToWrite(row: BlockedMessage): Int {
        val sim = simOf(row)
        if (sim == DEFAULT_SIM) return sim
        return if (runCatching { writer.simActive(sim) }.getOrNull() == false) DEFAULT_SIM else sim
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
        val attempt = MmsAttempt(row.id, probe.newestMmsId(), clock(), row.receivedAt)
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
        // T3-5: an id handed out again (a trip to 0.4.14) is another row: only the row of the attempt is resolved.
        val row = rows.get(attempt.rowId)?.takeIf { it.receivedAt == attempt.receivedAt }
        val found = row?.takeIf { it.migratedMsgId == null }?.let(::kindOf)
        // The row's kept file cannot be read now: the attempt cannot be resolved, so it stays (and holds every MMS of
        // the run) until it can; clearing it would let the row be written a second time (reviewer).
        if (found == MoveKind.Unreadable) return
        val kind = found as? MoveKind.Mms
        if (row != null && kind != null) {
            val whole = probe.findMmsAfter(attempt, kind.address, kind.identity)
            if (whole != null) {
                journal.setKind(RowKey.of(row), KIND_MMS)
                rows.mark(row.id, MigrationMark.written(whole))
            } else {
                probe.findHalfWrittenMms(attempt, kind.identity)?.let { half ->
                    check(writer.deleteMms(half)) { "A half written MMS could not be deleted" }
                }
            }
        }
        journal.attempt = null
    }

    /**
     * How the row went ([kind]) is kept before its mark (S2-2: never before the write worked). Null when the row is
     * gone meanwhile (F-1): the message stays where it is, nothing more is done for it.
     */
    private fun markWritten(row: BlockedMessage, storeId: Long, kind: Char): Unit? {
        journal.setKind(RowKey.of(row), kind)
        if (!rows.mark(row.id, MigrationMark.written(storeId))) return null
        takenWritten += takenKey(kind != KIND_TEXT, storeId)
        return Unit
    }

    private fun verify(row: BlockedMessage, kind: MoveKind, storeId: Long, ownWrite: Boolean): Outcome {
        // F-5: an MMS uMessage wrote is settled before every verify, until it worked.
        if (kind is MoveKind.Mms && ownWrite) {
            when (writer.settleMms(storeId, row.receivedAt)) {
                Settle.DONE -> Unit
                // S2-3: the user deleted it meanwhile: a failed verify, as for an SMS.
                Settle.GONE -> return failedVerify(row)
                Settle.FAILED -> return rowFailure()
            }
        }
        val ok = when (kind) {
            is MoveKind.Text -> probe.checkSms(storeId, kind.address, row.receivedAt, kind.body.length)
            is MoveKind.Mms -> probe.checkMms(storeId, kind.address, row.receivedAt / MILLIS, kind.identity)
            MoveKind.Nothing, MoveKind.Wait, MoveKind.Unreadable -> false
        }
        if (!ok) return failedVerify(row)
        // S7 and the thread's repair before the done mark: a kill in between repeats them, never skips them. The
        // thread comes from the store first, so a cache row that could not be built never skips the re-dating of an
        // MMS thread (emulator run 1: mmslib and the settle stamp an MMS thread with the time of the write).
        val cachedThread = runCatching { writer.cache(storeId, kind is MoveKind.Mms) }.getOrNull()
        val threadId = runCatching { writer.threadOf(storeId, kind is MoveKind.Mms) }.getOrNull() ?: cachedThread
        if (threadId != null && threads.add(threadId)) journal.pendingRepairs = threads.toSet()
        val isMms = kind is MoveKind.Mms
        done(row, storeId)
        takenWritten -= takenKey(isMms, storeId)
        takenDone += takenKey(isMms, storeId)
        moved++
        // After the done mark: a kill in between loses one unread count at most, it never counts one twice.
        if (row.unread) noteUnread(storeId, isMms)
        return Outcome.MOVED
    }

    private fun failedVerify(row: BlockedMessage): Outcome {
        rows.raiseTries(row.id)
        return Outcome.FAILED_VERIFY
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

    /**
     * The clean-up's clock first: a kill between the two never leaves a done row that is never cleaned up. [kind]: how
     * a row that is done at once went (a duplicate of another row's message).
     */
    private fun done(row: BlockedMessage, mark: Long, kind: Char? = null): Outcome {
        journal.lastDoneAt = clock()
        if (mark > 0L) journal.passOwed = true
        val key = RowKey.of(row)
        kind?.let { journal.setKind(key, it) }
        rows.mark(row.id, mark)
        if (journal.stallsOf(key) > 0) journal.setStalls(key, 0, 0L)
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

        /** S2-3: runs that stopped at one row before it is passed over for good. */
        const val MAX_STALLS = 3
        const val DEFAULT_SIM = -1
        const val STOPPED = "MIGRATION_STOPPED"
        const val ROW_STUCK = "MIGRATION_ROW_STUCK"
        private const val MILLIS = 1000L
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val KIND_TEXT = MigrationJournal.KIND_TEXT
        private const val KIND_MMS = MigrationJournal.KIND_MMS
        private const val KIND_MMS_FOUND = MigrationJournal.KIND_MMS_FOUND

        /** S17: old copies of moved rows are kept this long after the last one was done. */
        const val KEEP_OLD_COPIES_MS = 30 * DAY_MS

        /** A held MMS still downloading (step E's late result) waits this long before it is moved by its text. */
        const val LATE_DOWNLOAD_MS = DAY_MS
    }
}

/**
 * The message migration of section 8 on this phone. One run at a time, under [lock], which "Not spam" of the old
 * Blocked screen takes too (F-1). Its callers: the first run's "older spam" step ([firstRunStep], also when
 * uMessage becomes the default app again), and [runPending] at app start, from the daily alarm, the purge, a
 * restore of old Spam entries (S16) and a late private MMS result.
 */
@Suppress("TooManyFunctions")
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
        val result = migrate(context, noteUnreadNow = false, passFollows = true)
        // The unread counts it kept are noted once the pass that runs this step has sorted (emulator run 1: 67 entries
        // stayed until a later run). The sorting thread runs the pass to its end first.
        runCatching { afterPass(context.applicationContext) }
        !result.waiting
    }

    /** After the first run's pass: a run that notes the kept unread counts and empties the journal of them. */
    @VisibleForTesting
    @Volatile
    internal var afterPass: (Context) -> Unit = { app ->
        SpamSorting.execute(Runnable { runPending(app, announce = false, limit = RECEIVER_BATCH) })
    }

    /** One run; see [OlderSpamMover]. Throws when spamless.db cannot be read. */
    internal fun migrate(
        context: Context,
        limit: Int = Int.MAX_VALUE,
        noteUnreadNow: Boolean = true,
        passFollows: Boolean = false,
    ): MigrationResult {
        return synchronized(lock) {
            sweepHelpers(context)
            runWith(moverFor(context.applicationContext), limit, noteUnreadNow, passFollows)
        }
    }

    /** One run of [mover] and its clean-up; the caller holds [lock]. */
    private fun runWith(
        mover: OlderSpamMover,
        limit: Int,
        noteUnreadNow: Boolean,
        passFollows: Boolean,
    ): MigrationResult {
        val result = mover.run(limit, noteUnreadNow)
        runCatching { mover.cleanUp() }
        if (passFollows) mover.passHandled()
        return result
    }

    /**
     * Moves what still waits (app start, the daily checks, the purge, a restore) and asks for the history check of the
     * moved senders ([FirstRunTrigger.MIGRATION_FINISHED], 3.5) until a pass began after it was asked for (F-14,
     * reviewer); when the pages still say "waits", a [FirstRunTrigger.DEFAULT_AGAIN] pass instead, which clears that.
     * Before the first sort and while a pass works, the pass's own step moves them. [announce] false: the caller asks
     * for a pass of its own. [limit]: at most this many rows (a broadcast, F-19). Never throws; true when nothing
     * waits for the default role.
     */
    fun runPending(context: Context, announce: Boolean = true, limit: Int = Int.MAX_VALUE): Boolean {
        synchronized(lock) { sweepHelpers(context) }
        val state = runCatching { FirstRun.state(context).value }.getOrNull()
        val afterSort = state == null || state.allowsFullSort ||
            (state.sortPublished && state.phase == FirstRunPhase.CLEANUP)
        if (!afterSort) return !state.olderSpamWaiting
        // One mover for the run and the pass it may owe, under one lock.
        val result = runCatching {
            synchronized(lock) {
                val mover = moverFor(context.applicationContext)
                runWith(mover, limit, noteUnreadNow = true, passFollows = false).also { result ->
                    if (announce) runCatching { askForPass(context, mover, state, result) }
                }
            }
        }.onFailure { Log.w(TAG, "Older spam migration failed: ${it.javaClass.simpleName}") }
            .getOrNull() ?: return false
        return !result.waiting
    }

    /**
     * T-2: threads that still hold a helper draft of [ThreadRedate] (a run or a "Not spam" that died between its
     * writes) are dated again, at the start of every run and so at app start. The caller holds [lock]. Never throws.
     */
    @VisibleForTesting
    @Volatile
    internal var sweepHelpers: (Context) -> Unit = { ThreadRedate.sweep(it.applicationContext) }

    /** F-14: the owed check is asked for until a pass began after that; then it is done. The caller holds [lock]. */
    private fun askForPass(context: Context, mover: OlderSpamMover, state: FirstRunState?, result: MigrationResult) {
        val asked = mover.passRequestedAt()
        val ranSince = state != null && !state.active && asked > 0L && state.startedAt >= asked
        val stale = state?.olderSpamWaiting == true && !result.waiting
        when {
            stale -> ask(context, mover, FirstRunTrigger.DEFAULT_AGAIN)
            // Rows moved in this very run were not part of that pass: asked for again.
            mover.passOwed() && ranSince && result.moved == 0 -> mover.passHandled()
            mover.passOwed() -> ask(context, mover, FirstRunTrigger.MIGRATION_FINISHED)
            else -> Unit
        }
    }

    private fun ask(context: Context, mover: OlderSpamMover, trigger: FirstRunTrigger) {
        val now = System.currentTimeMillis()
        requestPass(context, trigger)
        mover.passRequested(now)
    }

    /**
     * A late result of a held MMS's private download (receivers/BlockedMmsDownloadReceiver, kept one release) was
     * recorded on row [blockedId]. A row marked "nothing to move" meanwhile has content now and is moved again (a row
     * already moved as text keeps its kept file, S2-2); then a bounded batch of waiting rows is moved. Never throws.
     */
    fun lateMmsResult(context: Context, blockedId: Long) {
        runCatching { AndroidMigrationRows(context.applicationContext).reopenNothingToMove(blockedId) }
        runPending(context, limit = RECEIVER_BATCH)
    }

    /** F-2: the migration stopped after too many failed verifies in this app version (the Older spam page says so). */
    fun stoppedForThisVersion(context: Context): Boolean = journal(context).haltedVersion == BuildConfig.VERSION_CODE

    /**
     * The rows of [rows] the migration will not move by itself (S2-3: stuck after [OlderSpamMover.MAX_STALLS] runs;
     * tries used up). Never throws.
     */
    fun stuck(context: Context, rows: List<BlockedMessage>): Set<Long> = runCatching {
        val journal = journal(context)
        rows.filter {
            it.migrateTries >= OlderSpamMover.MAX_TRIES || journal.stallsOf(RowKey.of(it)) >= OlderSpamMover.MAX_STALLS
        }.mapTo(HashSet()) { it.id } + lateFilesNotMoved(context, rows)
    }.getOrDefault(emptySet())

    /**
     * S2-2, F4-1: rows moved as text (mark > 0, kind text) whose kept pictures came later and stay only here: Older
     * spam lists them with a neutral line, the clean-up keeps them. The cheap checks come first; the journal is read
     * once. Call off the main thread. Never throws.
     */
    fun picturesOnlyHere(context: Context, rows: List<BlockedMessage>): Set<Long> = runCatching {
        val app = context.applicationContext
        val candidates = rows.filter { (it.migratedMsgId ?: 0L) > 0L && it.isMms && !it.mediaFile.isNullOrBlank() }
        if (candidates.isEmpty()) return@runCatching emptySet()
        val kinds = journal(app).kindsSnapshot()
        candidates.filter { kinds(RowKey.of(it)) == MigrationJournal.KIND_TEXT && keptFileThere(app, it) }
            .mapTo(HashSet()) { it.id }
    }.getOrDefault(emptySet())

    /**
     * F4-1: rows marked "nothing to move" whose kept file came later but is not moved: a run reopens every one it can
     * read (T3-3), so these cannot be read. Older spam lists them as stuck (red line); the clean-up keeps them. Call
     * off the main thread. Never throws.
     */
    fun lateFilesNotMoved(context: Context, rows: List<BlockedMessage>): Set<Long> = runCatching {
        val app = context.applicationContext
        rows.filter {
            it.migratedMsgId == MigrationMark.NOTHING_TO_MOVE && it.isMms && !it.mediaFile.isNullOrBlank() &&
                keptFileThere(app, it)
        }.mapTo(HashSet()) { it.id }
    }.getOrDefault(emptySet())

    /** Done rows Older spam still lists: [picturesOnlyHere] and [lateFilesNotMoved]. Call off the main thread. */
    fun keptHere(context: Context, rows: List<BlockedMessage>): Set<Long> =
        picturesOnlyHere(context, rows) + lateFilesNotMoved(context, rows)

    private fun keptFileThere(app: Context, row: BlockedMessage): Boolean =
        row.mediaFile?.let { BlockedMmsStore.pduFile(app, it) }?.isFile == true

    private fun journal(context: Context) = PrefsMigrationJournal(
        context.applicationContext.getSharedPreferences(PrefsMigrationJournal.PREFS, Context.MODE_PRIVATE),
    )

    /** The decision log line of a stopped migration or a stuck row: no sender, no text, only what happened. */
    internal fun reportTo(context: Context): (String) -> Unit = { reason ->
        UMessage.log(context).record(
            SpamLogEntry(System.currentTimeMillis(), "", OlderSpamMover.DEFAULT_SIM, "ERROR", reason, SOURCE)
        )
    }

    /** The migration's log tag, also [ThreadRedate]'s. */
    internal const val TAG = "uMessageReceive"
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

    /** S2-2: a file that is there but cannot be read (I/O, memory, not a downloaded MMS) is [KeptPdu.Unreadable]. */
    override fun identity(row: BlockedMessage): KeptPdu {
        val file = row.mediaFile?.let { BlockedMmsStore.pduFile(app, it) }
        if (file == null || !file.isFile) return KeptPdu.Missing
        val pdu = runCatching { file.readBytes() }.getOrNull() ?: return KeptPdu.Unreadable
        return MmsIdentities.of(pdu)?.let(KeptPdu::Ready) ?: KeptPdu.Unreadable
    }

    override fun keptFileExists(row: BlockedMessage): Boolean =
        row.mediaFile?.let { BlockedMmsStore.pduFile(app, it) }?.isFile == true

    override fun reopen(id: Long) = reopenNothingToMove(id)

    private companion object {
        const val TABLE = "blocked_messages"
        const val COLUMN_MARK = "migratedMsgId"
        const val SQL_CHUNK = 500
    }
}

/** The identity of a kept PDU, read the way mmslib's persist stores it. */
internal object MmsIdentities {
    /** Null when [pdu] is not a downloaded MMS (m-retrieve-conf) or cannot be parsed. */
    fun of(pdu: ByteArray): MmsIdentity? {
        val conf = runCatching { PduParser(pdu, true).parse() as? RetrieveConf }.getOrNull() ?: return null
        val messageId = runCatching { conf.messageId?.let(PduPersister::toIsoString) }.getOrNull()
        return MmsIdentity(
            messageId = messageId?.takeIf { it.isNotBlank() },
            textLength = runCatching { storedTextLength(conf) }.getOrDefault(-1),
            pduDate = runCatching { conf.date }.getOrNull()?.takeIf { it > 0L },
            addressCount = runCatching { addressCount(conf) }.getOrDefault(1),
        )
    }

    /** S2-4: the address rows mmslib's persist writes for [conf]: its CC, FROM and TO. */
    fun addressCount(conf: RetrieveConf): Int =
        (if (conf.from != null) 1 else 0) + conf.to.orEmpty().size + conf.cc.orEmpty().size

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

    /** One received MMS of the store: id, date, `date_sent` and message id. */
    private data class StoredMms(val id: Long, val date: Long, val dateSent: Long, val messageId: String?)

    override fun canWrite(): Boolean =
        runCatching { DefaultSmsRole.status(app) == DefaultSmsRole.Status.Held }.getOrDefault(false)

    override fun findSms(address: String, body: String, date: Long): Long? = ids(
        Sms.CONTENT_URI,
        "${Sms.ADDRESS} = ? AND ${Sms.BODY} = ? AND ${Sms.DATE} = ? AND ${Sms.TYPE} = ?",
        arrayOf(address, body, "$date", "${Sms.MESSAGE_TYPE_INBOX}"),
    ).firstOrNull()

    override fun findMms(identity: MmsIdentity, address: String, dateSeconds: Long): Long? {
        identity.messageId?.let { messageId ->
            ids(Mms.CONTENT_URI, "${Mms.MESSAGE_ID} = ? AND $RECEIVED_MMS", arrayOf(messageId)).firstOrNull()
                ?.let { return it }
        }
        // S2-1: a restored MMS has no message id in the store: the sender and the time, or the PDU's own date. A row
        // with another carrier id is another message.
        val noId = if (identity.messageId != null) " AND (${Mms.MESSAGE_ID} IS NULL OR ${Mms.MESSAGE_ID} = '')" else ""
        val byDate = ids(Mms.CONTENT_URI, "${Mms.DATE} = ? AND $RECEIVED_MMS$noId", arrayOf("$dateSeconds"))
        val bySent = identity.pduDate?.let {
            ids(Mms.CONTENT_URI, "${Mms.DATE_SENT} = ? AND $RECEIVED_MMS$noId", arrayOf("$it"))
        }.orEmpty()
        return (byDate + bySent).distinct().firstOrNull { sameSender(address, mmsSender(it)) }
    }

    /** F-20: no LIMIT in the sort order; the first row of the newest-first list. */
    override fun newestMmsId(): Long =
        query(Mms.CONTENT_URI, arrayOf(BaseColumns._ID), sortOrder = "${BaseColumns._ID} DESC")
            .use { if (it.moveToFirst()) it.getLong(0) else 0L }

    override fun findMmsAfter(attempt: MmsAttempt, address: String, identity: MmsIdentity): Long? =
        newerReceived(attempt).firstOrNull { mms ->
            ownedBy(mms, identity, half = false) && addressCount(mms.id) >= identity.addressCount &&
                sameSender(address, mmsSender(mms.id))
        }?.id

    override fun findHalfWrittenMms(attempt: MmsAttempt, identity: MmsIdentity): Long? =
        newerReceived(attempt).firstOrNull { mms ->
            ownedBy(mms, identity, half = true) && addressCount(mms.id) < identity.addressCount
        }?.id

    /**
     * Whether [mms] is the attempt's own write: the message id; without one the PDU's own date (S2-5), which a cut-off
     * row carries as its date and a whole one as `date_sent` (mmslib's update after its persist).
     */
    private fun ownedBy(mms: StoredMms, identity: MmsIdentity, half: Boolean): Boolean {
        identity.messageId?.let { return mms.messageId == it }
        val pduDate = identity.pduDate ?: return false
        return mms.date == pduDate || (!half && mms.dateSent == pduDate)
    }

    /** Received MMS newer than the attempt's newest id. */
    private fun newerReceived(attempt: MmsAttempt): List<StoredMms> = query(
        Mms.CONTENT_URI, arrayOf(BaseColumns._ID, Mms.DATE, Mms.DATE_SENT, Mms.MESSAGE_ID),
        "${BaseColumns._ID} > ? AND $RECEIVED_MMS", arrayOf("${attempt.newestBefore}"), "${BaseColumns._ID} ASC",
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    StoredMms(
                        c.number(BaseColumns._ID), c.number(Mms.DATE), c.number(Mms.DATE_SENT),
                        c.textOrNull(Mms.MESSAGE_ID),
                    )
                )
            }
        }
    }

    override fun messageIdOf(id: Long): String? =
        query(ContentUris.withAppendedId(Mms.CONTENT_URI, id), arrayOf(Mms.MESSAGE_ID))
            .use { if (it.moveToFirst()) it.textOrNull(Mms.MESSAGE_ID)?.takeIf(String::isNotEmpty) else null }

    override fun visible(id: Long, isMms: Boolean): Boolean = MissingSim.readable(app, id, isMms)

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
        // S2-1: a store row without a message id (a restored MMS) is checked by sender, time and text.
        val sameId = identity.messageId == null || messageId.isNullOrEmpty() || messageId == identity.messageId
        return date == dateSeconds && box == Mms.MESSAGE_BOX_INBOX.toLong() && sameId &&
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

    override fun deleteSms(id: Long): Boolean =
        app.contentResolver.delete(ContentUris.withAppendedId(Sms.CONTENT_URI, id), null, null) == 1

    override fun simActive(subscriptionId: Int): Boolean? = MissingSim.active(app, subscriptionId)

    override fun settleMms(id: Long, date: Long): Settle {
        val values = ContentValues().apply {
            put(Mms.DATE, date / MILLIS)
            put(Mms.READ, 1)
            put(Mms.SEEN, 1)
        }
        val uri = ContentUris.withAppendedId(Mms.CONTENT_URI, id)
        if (app.contentResolver.update(uri, values, null, null) == 1) return Settle.DONE
        // S2-3: nothing changed: the user deleted it meanwhile, or the store refused the update.
        val there = query(uri, arrayOf(BaseColumns._ID)).use { it.count > 0 }
        return if (there) Settle.FAILED else Settle.GONE
    }

    override fun cache(id: Long, isMms: Boolean): Long? {
        val message = message(id, isMms) ?: return null
        app.messagesDB.insertOrUpdate(message)
        return message.threadId
    }

    /**
     * The threads were stamped with the time of the writes (an MMS thread also by the settle, whose date update the
     * provider's trigger answers with "now"): the store dates them by their newest message again ([ThreadRedate],
     * also an MMS-only thread), and the chat row too, so a migrated chat never shows at the top as "Now". Throws when
     * a thread could not be dated, so the threads stay in the journal and the next run dates them again (T-2).
     */
    override fun repair(threadIds: Set<Long>) {
        var dated = true
        for (threadId in threadIds) {
            if (!ThreadRedate.redate(app, threadId)) dated = false
            app.getConversations(threadId).firstOrNull()?.let { conv ->
                val newest = (ThreadRedate.newestDate(app.contentResolver, threadId) / MILLIS).toInt()
                if (newest > 0 && conv.date > newest) conv.date = newest
                app.insertOrUpdateConversation(conv)
            }
        }
        runCatching { refreshMessages() }
        runCatching { refreshConversations() }
        check(dated) { "Moved chats not dated" }
    }

    override fun threadOf(id: Long, isMms: Boolean): Long? {
        val uri = ContentUris.withAppendedId(if (isMms) Mms.CONTENT_URI else Sms.CONTENT_URI, id)
        return query(uri, arrayOf(Sms.THREAD_ID)).use { if (it.moveToFirst()) it.getLong(0) else null }
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

    /** S2-4: mmslib writes one address row per address, last: fewer than the PDU has means its write was cut off. */
    private fun addressCount(id: Long): Int =
        query(Uri.parse("${Mms.CONTENT_URI}/$id/addr"), arrayOf(Mms.Addr.ADDRESS)).use { it.count }

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

        /** F-16: a downloaded MMS in the inbox (m-retrieve-conf), never a "Tap to download" notice. */
        const val RECEIVED_MMS = "${Mms.MESSAGE_BOX} = ${Mms.MESSAGE_BOX_INBOX} AND " +
            "${Mms.MESSAGE_TYPE} = ${PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF}"
    }
}

/**
 * The journal in its own preferences, written at once: a kill right after must find it. S2-6: a write that did not
 * reach the disk throws [JournalWriteFailed]. Rows are keyed by id and arrival (S2-2).
 */
@Suppress("TooManyFunctions")
internal class PrefsMigrationJournal(private val prefs: SharedPreferences) : MigrationJournal {
    private fun write(change: SharedPreferences.Editor.() -> Unit) {
        if (!prefs.edit().apply(change).commit()) throw JournalWriteFailed()
    }

    override var attempt: MmsAttempt?
        get() = if (prefs.contains(KEY_ROW)) {
            MmsAttempt(
                prefs.getLong(KEY_ROW, 0L), prefs.getLong(KEY_NEWEST, 0L), prefs.getLong(KEY_STARTED, 0L),
                prefs.getLong(KEY_RECEIVED, 0L),
            )
        } else {
            null
        }
        set(value) = write {
            if (value == null) {
                remove(KEY_ROW)
                remove(KEY_NEWEST)
                remove(KEY_STARTED)
                remove(KEY_RECEIVED)
            } else {
                putLong(KEY_ROW, value.rowId)
                putLong(KEY_NEWEST, value.newestBefore)
                putLong(KEY_STARTED, value.startedAt)
                putLong(KEY_RECEIVED, value.receivedAt)
            }
        }

    override var lastDoneAt: Long
        get() = prefs.getLong(KEY_DONE_AT, 0L)
        set(value) = write { putLong(KEY_DONE_AT, value) }

    override var haltedVersion: Int
        get() = prefs.getInt(KEY_HALTED, -1)
        set(value) = write { putInt(KEY_HALTED, value) }

    override var pendingRepairs: Set<Long>
        get() = prefs.getStringSet(KEY_REPAIRS, emptySet()).orEmpty().mapNotNullTo(HashSet()) { it.toLongOrNull() }
        set(value) = write { putStringSet(KEY_REPAIRS, value.mapTo(HashSet()) { it.toString() }) }

    override var passOwed: Boolean
        get() = prefs.getBoolean(KEY_PASS_OWED, false)
        set(value) = write { putBoolean(KEY_PASS_OWED, value) }

    override var passRequestedAt: Long
        get() = prefs.getLong(KEY_PASS_ASKED, 0L)
        set(value) = write { putLong(KEY_PASS_ASKED, value) }

    override var pendingUnread: Set<String>
        get() = prefs.getStringSet(KEY_UNREAD, emptySet()).orEmpty().toSet()
        set(value) = write { putStringSet(KEY_UNREAD, HashSet(value)) }

    override fun kindOf(key: RowKey): Char? = valueOf(KEY_KINDS, key)?.singleOrNull()

    /** Every kind, read once (the Spam tab's count reads many rows, reviewer). */
    fun kindsSnapshot(): (RowKey) -> Char? {
        val byKey = entries(KEY_KINDS)
            .associate { it.substringBefore(':') to it.substringAfter(':', "").singleOrNull() }
        return { key -> byKey[keyOf(key)] }
    }

    override fun setKind(key: RowKey, kind: Char) = setValue(KEY_KINDS, key, kind.toString())

    /** Stored as "<count>/<day>". */
    override fun stallsOf(key: RowKey): Int = valueOf(KEY_STALLS, key)?.substringBefore('/')?.toIntOrNull() ?: 0

    override fun stallDayOf(key: RowKey): Long =
        valueOf(KEY_STALLS, key)?.substringAfter('/', "")?.toLongOrNull() ?: -1L

    override fun setStalls(key: RowKey, stalls: Int, day: Long) =
        setValue(KEY_STALLS, key, stalls.takeIf { it > 0 }?.let { "$it/$day" })

    override var stallsVersion: Int
        get() = prefs.getInt(KEY_STALLS_VERSION, -1)
        set(value) = write { putInt(KEY_STALLS_VERSION, value) }

    override fun clearStalls() = synchronized(LOCK) { write { remove(KEY_STALLS) } }

    override fun forget(keys: Collection<RowKey>) = synchronized(LOCK) {
        val gone = keys.mapTo(HashSet(), ::keyOf)
        write {
            for (set in listOf(KEY_KINDS, KEY_STALLS)) {
                putStringSet(set, HashSet(entries(set).filterNot { it.substringBefore(':') in gone }))
            }
        }
    }

    private fun keyOf(key: RowKey) = "${key.id}@${key.receivedAt}"

    private fun entries(set: String): Set<String> = prefs.getStringSet(set, emptySet()).orEmpty()

    private fun valueOf(set: String, key: RowKey): String? {
        val prefix = keyOf(key) + ":"
        return entries(set).firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
    }

    /** [value] null removes the entry. */
    private fun setValue(set: String, key: RowKey, value: String?) = synchronized(LOCK) {
        val name = keyOf(key)
        val next = entries(set).filterNot { it.substringBefore(':') == name } +
            listOfNotNull(value?.let { "$name:$it" })
        write { putStringSet(set, HashSet(next)) }
    }

    companion object {
        /** New in provider truth; not a `spamless_` name. Never rename: a killed migration resumes from it. */
        const val PREFS = "umessage_older_spam"
        private val LOCK = Any()
        private const val KEY_ROW = "attempt_row"
        private const val KEY_NEWEST = "attempt_newest_mms"
        private const val KEY_DONE_AT = "last_done_at"
        private const val KEY_HALTED = "halted_version"
        private const val KEY_STARTED = "attempt_started_at"
        private const val KEY_RECEIVED = "attempt_received_at"
        private const val KEY_REPAIRS = "pending_repairs"
        private const val KEY_PASS_OWED = "pass_owed"
        private const val KEY_PASS_ASKED = "pass_requested_at"
        private const val KEY_UNREAD = "pending_unread"

        /**
         * Keyed by id and arrival (S2-2). The earlier key "row_kinds" (by id only, only in unshipped step F builds) is
         * not read: rows it named stay on the safe side (kept, listed); test runs start from fresh snapshots (T3-6).
         */
        private const val KEY_KINDS = "row_kinds_v2"
        private const val KEY_STALLS = "row_stalls"
        private const val KEY_STALLS_VERSION = "row_stalls_version"
    }
}
