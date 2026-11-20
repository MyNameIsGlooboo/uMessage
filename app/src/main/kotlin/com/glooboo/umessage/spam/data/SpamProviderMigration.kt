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
 * of its text as the verify reads it, and the date its PDU carries (seconds; null when none), which a write that a kill
 * cut off leaves on the row.
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

/** The end of one run. [waiting]: there is work but uMessage may not write (not the default SMS app). */
internal data class MigrationResult(
    val waiting: Boolean = false,
    val moved: Int = 0,
    val failed: Int = 0,
    val halted: Boolean = false,
    /** Rows still to move or to verify after the run (also those that wait for a late MMS download). */
    val left: Int = 0,
)

/** The held rows in spamless.db and their kept MMS files. */
internal interface MigrationRows {
    fun all(): List<BlockedMessage>

    fun get(id: Long): BlockedMessage?

    /** Sets migratedMsgId of row [id]; false when the row is gone. */
    fun mark(id: Long, mark: Long): Boolean

    /** One more failed try of row [id] (migrateTries). */
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

    /** S4: an inbox MMS with the carrier message id; without one, with this sender and time (seconds). */
    fun findMms(identity: MmsIdentity, address: String, dateSeconds: Long): Long?

    /** The newest MMS id of the store, 0 when it holds none. */
    fun newestMmsId(): Long

    /**
     * The inbox MMS that the write [attempt] left (a kill or a failure cut it off): newer than its newest id, from
     * [address], with the message id of [identity]; without one, dated as mmslib dates a write (the PDU's date, or
     * around [MmsAttempt.startedAt]), so a later MMS of the same sender is not taken for it.
     */
    fun findMmsAfter(attempt: MmsAttempt, address: String, identity: MmsIdentity): Long?

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

    /** The kept PDU of [row] through mmslib; its id, or 0 when nothing was written. */
    fun writeMms(row: BlockedMessage): Long

    /** Date, read and seen of MMS [id] in one update (mmslib wrote the time of the write, unread). */
    fun settleMms(id: Long, date: Long): Boolean

    /** S7: the cache row of the moved message, so search, the digest and the row preview see it. Its thread id. */
    fun cache(id: Long, isMms: Boolean): Long?

    /** After each sender: the thread dated by its newest message and the chat row cached again. */
    fun repair(threadIds: Set<Long>)

    /**
     * A moved message the user had not opened in the old Spam screen: when its sender's chat is in Spam (a one-to-one
     * chat whose sender is judged Spam), its Spam row counts one more unread text.
     */
    fun noteUnread(storeId: Long, isMms: Boolean, address: String) = Unit
}

/** What a run keeps across a kill, outside spamless.db. */
internal interface MigrationJournal {
    var attempt: MmsAttempt?

    /** Wall-clock time of the last row marked done; the clean-up waits 30 days after it. 0 = never. */
    var lastDoneAt: Long

    /** The app version in which the migration stopped after too many failures; -1 = it did not. */
    var haltedVersion: Int

    /** Threads written to whose date and chat row are not repaired yet; a kill leaves them for the next run. */
    var pendingRepairs: Set<Long>
}

/**
 * Section 8: moves the messages the 0.4.14 filter held in spamless.db (blocked_messages) into the phone's message
 * store, once. The owner marked this step as very sensitive: a message is never lost and never written twice.
 *
 * Per row, oldest first, in batches of 50:
 * 1. A row with a done mark is skipped; it is never written again (S2).
 * 2. Probe (S4): SMS by sender, text and exact time; MMS by its carrier message id, else by sender and time. Found:
 *    its id is the mark and the row goes to the verify.
 * 3. Write: an SMS into the inbox, dated when it arrived, read and seen, on its SIM (a SIM this phone does not know
 *    any more is written without one); a kept MMS with mmslib, then its date, read and seen in one update; an MMS
 *    with text only as an SMS; an MMS placeholder with nothing kept is marked "nothing to move".
 * 4. The new id is saved at once as the mark, before the verify (S2). An MMS write is journaled first, so a kill
 *    between mmslib's write and the mark finds the row again by id instead of writing it twice.
 * 5. Verify by that id: sender, time, text length. A failed verify raises migrateTries and never rewrites. At most
 *    2 tries per row. More than 5 failed rows stop the migration for this app version; that is written to the
 *    decision log, and it does not loop.
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
    private var moved = 0
    private var failed = 0
    private val threads = LinkedHashSet<Long>()

    fun run(): MigrationResult {
        moved = 0
        failed = 0
        threads.clear()
        val todo = rows.all().count(::needsWork)
        return when {
            todo == 0 && journal.pendingRepairs.isEmpty() -> MigrationResult(left = 0)
            todo > 0 && journal.haltedVersion == appVersion -> MigrationResult(halted = true, left = todo)
            !writer.canWrite() -> MigrationResult(waiting = todo > 0, left = todo)
            else -> {
                // A kill after the last rows of a batch were done, before their threads were dated again.
                threads += journal.pendingRepairs
                repairTouched()
                if (todo == 0) MigrationResult(left = 0) else moveAll()
            }
        }
    }

    /** Every row that needs work, oldest first, in batches; stops after more than [MAX_FAILED_ROWS] failed rows. */
    private fun moveAll(): MigrationResult {
        // A row whose cut-off MMS write could not be looked at waits for the next run: writing it now could double it.
        val unsure = runCatching { resumeAttempt() }
            .onFailure { Log.w(TAG, "Older spam: a cut-off MMS write could not be checked") }
            .exceptionOrNull()?.let { journal.attempt?.rowId }
        val work = rows.all().filter { needsWork(it) && it.id != unsure }
            .sortedWith(compareBy({ it.receivedAt }, { it.id }))
        var halted = false
        for (batch in work.chunked(BATCH)) {
            for (row in batch) {
                if (!moveSafely(row)) failed++
                halted = failed > MAX_FAILED_ROWS
                if (halted) break
            }
            repairTouched()
            if (halted) break
        }
        if (halted) halt()
        return MigrationResult(moved = moved, failed = failed, halted = halted, left = leftCount())
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
        if (files.isNotEmpty()) runCatching { rows.deleteMedia(files) }
        return done.size
    }

    private fun needsWork(row: BlockedMessage): Boolean =
        row.migrateTries < MAX_TRIES && (row.migratedMsgId == null || MigrationMark.isWritten(row.migratedMsgId))

    private fun leftCount(): Int = rows.all().count(::needsWork)

    /** A row that throws (a store that cannot be read, a database error) counts as one failed try. */
    private fun moveSafely(row: BlockedMessage): Boolean = runCatching { move(row) }.getOrElse {
        Log.w(TAG, "Older spam row not moved: ${it.javaClass.simpleName}")
        runCatching { rows.raiseTries(row.id) }
        false
    }

    private fun move(row: BlockedMessage): Boolean {
        val kind = kindOf(row)
        val mark = row.migratedMsgId
        // Written before (a kill came before the verify, or the verify failed once): verify again, never rewrite.
        if (mark != null && MigrationMark.isWritten(mark)) return verify(row, kind, MigrationMark.storeIdOf(mark))
        return when (kind) {
            MoveKind.Nothing -> done(row.id, MigrationMark.NOTHING_TO_MOVE)
            MoveKind.Wait -> true
            is MoveKind.Text -> moveText(row, kind)
            is MoveKind.Mms -> moveMms(row, kind)
        }
    }

    private fun kindOf(row: BlockedMessage): MoveKind {
        val address = targetAddress(row.address)
        if (!row.isMms) return MoveKind.Text(address, row.body)
        if (row.hasStoredMedia) rows.identity(row)?.let { return MoveKind.Mms(address, it) }
        val downloading = row.media == MmsMedia.DOWNLOADING && clock() - row.receivedAt < LATE_DOWNLOAD_MS
        return when {
            downloading -> MoveKind.Wait
            row.hasText -> MoveKind.Text(address, row.body)
            else -> MoveKind.Nothing
        }
    }

    private fun moveText(row: BlockedMessage, kind: MoveKind.Text): Boolean {
        var id = probe.findSms(kind.address, kind.body, row.receivedAt) ?: 0L
        if (id <= 0L) id = writer.writeSms(kind.address, kind.body, row.receivedAt, simOf(row))
        // A SIM taken out since: newer Android refuses its id. Look first (nothing was written), then without a SIM.
        if (id <= 0L && simOf(row) != DEFAULT_SIM) {
            id = probe.findSms(kind.address, kind.body, row.receivedAt)
                ?: writer.writeSms(kind.address, kind.body, row.receivedAt, DEFAULT_SIM)
        }
        if (id <= 0L) return failedTry(row)
        rows.mark(row.id, MigrationMark.written(id))
        return verify(row, kind, id)
    }

    private fun moveMms(row: BlockedMessage, kind: MoveKind.Mms): Boolean {
        val found = probe.findMms(kind.identity, kind.address, row.receivedAt / MILLIS)
        if (found != null) {
            rows.mark(row.id, MigrationMark.written(found))
            return verify(row, kind, found)
        }
        val attempt = MmsAttempt(row.id, probe.newestMmsId(), clock())
        journal.attempt = attempt
        // A persist that failed half way may have left a row: it is taken, never written a second time.
        val id = writer.writeMms(row).takeIf { it > 0L } ?: probe.findMmsAfter(attempt, kind.address, kind.identity)
        if (id == null) {
            journal.attempt = null
            return failedTry(row)
        }
        rows.mark(row.id, MigrationMark.written(id))
        writer.settleMms(id, row.receivedAt)
        journal.attempt = null
        return verify(row, kind, id)
    }

    /**
     * A kill during an MMS write: the row it left is found by id and settled, never written again. The journal is
     * cleared only when this ended; throws when the store cannot be read (the row then waits).
     */
    private fun resumeAttempt() {
        val attempt = journal.attempt ?: return
        val row = rows.get(attempt.rowId)
        val kind = row?.let(::kindOf) as? MoveKind.Mms
        val mark = row?.migratedMsgId
        if (row != null && kind != null) {
            val storeId = when {
                mark == null -> probe.findMmsAfter(attempt, kind.address, kind.identity)
                    ?.also { rows.mark(row.id, MigrationMark.written(it)) }
                MigrationMark.isWritten(mark) -> MigrationMark.storeIdOf(mark)
                else -> null
            }
            storeId?.let { writer.settleMms(it, row.receivedAt) }
        }
        journal.attempt = null
    }

    private fun verify(row: BlockedMessage, kind: MoveKind, storeId: Long): Boolean {
        val ok = when (kind) {
            is MoveKind.Text -> probe.checkSms(storeId, kind.address, row.receivedAt, kind.body.length)
            is MoveKind.Mms -> probe.checkMms(storeId, kind.address, row.receivedAt / MILLIS, kind.identity)
            MoveKind.Nothing, MoveKind.Wait -> false
        }
        if (!ok) return failedTry(row)
        // S7 and the thread's repair before the done mark: a kill in between repeats them, never skips them.
        runCatching { writer.cache(storeId, kind is MoveKind.Mms) }.getOrNull()?.let { threadId ->
            if (threads.add(threadId)) journal.pendingRepairs = threads.toSet()
        }
        done(row.id, storeId)
        moved++
        // After the done mark: a kill in between loses one unread count at most, it never counts one twice.
        if (row.unread) runCatching { writer.noteUnread(storeId, kind is MoveKind.Mms, addressOf(kind)) }
        return true
    }

    private fun addressOf(kind: MoveKind): String = when (kind) {
        is MoveKind.Text -> kind.address
        is MoveKind.Mms -> kind.address
        MoveKind.Nothing, MoveKind.Wait -> ""
    }

    /** The clean-up's clock first: a kill between the two never leaves a done row that is never cleaned up. */
    private fun done(rowId: Long, mark: Long): Boolean {
        journal.lastDoneAt = clock()
        rows.mark(rowId, mark)
        return true
    }

    private fun failedTry(row: BlockedMessage): Boolean {
        rows.raiseTries(row.id)
        return false
    }

    private fun repairTouched() {
        if (threads.isEmpty()) return
        runCatching { writer.repair(threads.toSet()) }.onFailure { Log.w(TAG, "Moved chats not repaired", it) }
        threads.clear()
        journal.pendingRepairs = emptySet()
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

        /** S17: old copies of moved rows are kept this long after the last one was done. */
        const val KEEP_OLD_COPIES_MS = 30 * DAY_MS

        /** A held MMS still downloading (step E's late result) waits this long before it is moved by its text. */
        const val LATE_DOWNLOAD_MS = DAY_MS
    }
}

/**
 * The message migration of section 8 on this phone. One run at a time; nothing runs while [ProviderTruthFlag] is off.
 * Its three callers: the first run's "older spam" step ([firstRunStep], also when uMessage becomes the default app
 * again), the daily checks and the purge ([runPending]), and a restore of old Spam entries (S16).
 */
object SpamProviderMigration {
    private val lock = Any()

    /** Tests replace how the mover is made. */
    @VisibleForTesting
    @Volatile
    internal var moverFor: (Context) -> OlderSpamMover = AndroidOlderSpam::mover

    /**
     * Step 3 of the first run (3.5): true when everything is moved or nothing is left to move; false when it must wait
     * because uMessage is not the default SMS app. A failure (spamless.db cannot be read) throws, so the pass stops
     * and is tried again. Set as [FirstRun.olderSpamMigration] at app start.
     */
    val firstRunStep = OlderSpamMigration { context -> !migrate(context).waiting }

    /** One run; see [OlderSpamMover]. With the flag off nothing happens. Throws when spamless.db cannot be read. */
    internal fun migrate(context: Context): MigrationResult {
        if (!ProviderTruthFlag.enabled) return MigrationResult()
        val app = context.applicationContext
        return synchronized(lock) {
            val mover = moverFor(app)
            mover.run().also { runCatching { mover.cleanUp() } }
        }
    }

    /**
     * Moves what still waits (the daily checks, the purge, a restore) and, when it moved anything, asks for the
     * history check of the moved senders ([FirstRunTrigger.MIGRATION_FINISHED], 3.5). [announce] false: the caller
     * asks for a pass of its own. Never throws; true when nothing waits for the default role any more.
     */
    fun runPending(context: Context, announce: Boolean = true): Boolean {
        if (!ProviderTruthFlag.enabled) return true
        val state = runCatching { FirstRun.state(context).value }.getOrNull()
        // Before the first sort, and while a pass works, the pass's own "older spam" step moves them (3.5).
        if (state != null && !state.allowsFullSort) return !state.olderSpamWaiting
        val result = runCatching { migrate(context) }
            .onFailure { Log.w(TAG, "Older spam migration failed: ${it.javaClass.simpleName}") }
            .getOrNull() ?: return false
        // The pages still say "waits": a pass with the older-spam step clears that, then checks and sorts as below.
        val stale = state?.olderSpamWaiting == true && !result.waiting
        when {
            !announce -> Unit
            stale -> FirstRun.request(context, FirstRunTrigger.DEFAULT_AGAIN)
            result.moved > 0 -> FirstRun.request(context, FirstRunTrigger.MIGRATION_FINISHED)
        }
        return !result.waiting
    }

    /**
     * A late result of a held MMS's private download (receivers/BlockedMmsDownloadReceiver, kept one release) was
     * recorded on row [blockedId]. A row marked "nothing to move" meanwhile has content now and is moved again; then
     * the waiting rows are moved. Never throws; nothing with the flag off.
     */
    fun lateMmsResult(context: Context, blockedId: Long) {
        if (!ProviderTruthFlag.enabled) return
        runCatching { AndroidMigrationRows(context.applicationContext).reopenNothingToMove(blockedId) }
        runPending(context)
    }

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
        val conf = runCatching { PduParser(pdu, true).parse() as? RetrieveConf }.getOrNull() ?: return null
        val messageId = runCatching { conf.messageId?.let(PduPersister::toIsoString) }.getOrNull()
        return MmsIdentity(
            messageId = messageId?.takeIf { it.isNotBlank() },
            textLength = BlockedMmsContent.parse(pdu)?.text.orEmpty().length,
            pduDate = runCatching { conf.date }.getOrNull()?.takeIf { it > 0L },
        )
    }

    private companion object {
        const val TABLE = "blocked_messages"
        const val COLUMN_MARK = "migratedMsgId"
        const val SQL_CHUNK = 500
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
            return ids(Mms.CONTENT_URI, "${Mms.MESSAGE_ID} = ? AND $INBOX_MMS", arrayOf(messageId)).firstOrNull()
        }
        return ids(Mms.CONTENT_URI, "${Mms.DATE} = ? AND $INBOX_MMS", arrayOf("$dateSeconds"))
            .firstOrNull { sameSender(address, mmsSender(it)) }
    }

    override fun newestMmsId(): Long =
        ids(Mms.CONTENT_URI, null, null, sortOrder = "${Mms._ID} DESC LIMIT 1").firstOrNull() ?: 0L

    override fun findMmsAfter(attempt: MmsAttempt, address: String, identity: MmsIdentity): Long? {
        val started = attempt.startedAt / MILLIS
        val mmslibDated = (started - WRITE_SLACK_SECONDS)..(started + WRITE_LATE_SECONDS)
        return ids(Mms.CONTENT_URI, "${Mms._ID} > ? AND $INBOX_MMS", arrayOf("${attempt.newestBefore}"))
            .firstOrNull { id ->
                val (date, messageId) = query(
                    ContentUris.withAppendedId(Mms.CONTENT_URI, id), arrayOf(Mms.DATE, Mms.MESSAGE_ID),
                ).use { c -> if (c.moveToFirst()) c.number(Mms.DATE) to c.textOrNull(Mms.MESSAGE_ID) else 0L to null }
                val ours = if (identity.messageId != null) {
                    messageId == identity.messageId
                } else {
                    date == identity.pduDate || date in mmslibDated
                }
                ours && sameSender(address, mmsSender(id))
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

    override fun writeMms(row: BlockedMessage): Long =
        RestoredMmsDelivery.persistKept(app, row)?.let(ContentUris::parseId) ?: 0L

    override fun settleMms(id: Long, date: Long): Boolean {
        val values = ContentValues().apply {
            put(Mms.DATE, date / MILLIS)
            put(Mms.READ, 1)
            put(Mms.SEEN, 1)
        }
        return app.contentResolver.update(ContentUris.withAppendedId(Mms.CONTENT_URI, id), values, null, null) == 1
    }

    override fun cache(id: Long, isMms: Boolean): Long? {
        val message = if (isMms) {
            app.getMMS(ContentUris.withAppendedId(Mms.CONTENT_URI, id))
        } else {
            smsMessage(id)
        } ?: return null
        app.messagesDB.insertOrUpdate(message)
        return message.threadId
    }

    /** The threads were stamped with the time of the writes: dated by their newest message, their rows cached again. */
    override fun repair(threadIds: Set<Long>) {
        for (threadId in threadIds) {
            runCatching { app.recomputeThreadDate(threadId) }
            runCatching { app.getConversations(threadId).firstOrNull()?.let { app.insertOrUpdateConversation(it) } }
        }
        runCatching { refreshMessages() }
        runCatching { refreshConversations() }
    }

    override fun noteUnread(storeId: Long, isMms: Boolean, address: String) {
        if (SenderAddress.isUnreadable(address)) return
        val message = if (isMms) {
            app.getMMS(ContentUris.withAppendedId(Mms.CONTENT_URI, storeId))
        } else {
            smsMessage(storeId)
        } ?: return
        // Only a one-to-one chat can be in Spam (R4).
        if (message.participants.size > 1) return
        val judge = (UMessage.filter(app) as? DefaultSpamFilter)?.senderJudge ?: return
        val spam = judge.verdict(address) as? SenderVerdict.Spam ?: return
        SpamThreads.noteArrival(app, message.threadId, address, spam.cause, System.currentTimeMillis())
    }

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

    /** The text of MMS [id] as the kept PDU's text is read: the non-blank text parts, one per line. */
    private fun mmsTextLength(id: Long): Int = query(
        Uri.parse("${Mms.CONTENT_URI}/part"), arrayOf(Mms.Part.TEXT),
        "${Mms.Part.MSG_ID} = ? AND ${Mms.Part.CONTENT_TYPE} = ?", arrayOf("$id", ContentType.TEXT_PLAIN),
        sortOrder = Mms.Part.SEQ,
    ).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0).orEmpty()) }
    }.filter { it.isNotBlank() }.joinToString("\n").trim().length

    private fun ids(uri: Uri, selection: String?, args: Array<String>?, sortOrder: String = "${BaseColumns._ID} ASC") =
        query(uri, arrayOf(BaseColumns._ID), selection, args, sortOrder).use { c ->
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
        const val INBOX_MMS = "${Mms.MESSAGE_BOX} = ${Mms.MESSAGE_BOX_INBOX}"
    }
}

/** The journal in its own preferences, written at once: a kill right after must find it. */
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

    companion object {
        /** New in provider truth; not a `spamless_` name. Never rename: a killed migration resumes from it. */
        const val PREFS = "umessage_older_spam"
        private const val KEY_ROW = "attempt_row"
        private const val KEY_NEWEST = "attempt_newest_mms"
        private const val KEY_DONE_AT = "last_done_at"
        private const val KEY_HALTED = "halted_version"
        private const val KEY_STARTED = "attempt_started_at"
        private const val KEY_REPAIRS = "pending_repairs"
    }
}
