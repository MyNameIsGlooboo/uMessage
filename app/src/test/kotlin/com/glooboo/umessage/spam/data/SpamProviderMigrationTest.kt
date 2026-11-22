package com.glooboo.umessage.spam.data

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.Telephony
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.backup.BlockedBackupSupport
import com.glooboo.umessage.models.BlockedBackup
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FirstRun
import com.glooboo.umessage.spam.FirstRunPhase
import com.glooboo.umessage.spam.FirstRunState
import com.glooboo.umessage.spam.FirstRunStore
import com.glooboo.umessage.spam.FirstRunTrigger
import com.glooboo.umessage.spam.data.OlderSpamWorld.Companion.DAY
import com.glooboo.umessage.spam.data.OlderSpamWorld.Companion.HOUR
import com.glooboo.umessage.spam.data.OlderSpamWorld.Companion.NOW
import com.glooboo.umessage.spam.data.OlderSpamWorld.Companion.SENDER
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric

/**
 * The message migration on this app's own parts (section 8, 12.1): the first-run step, the marks in a real
 * spamless.db, the journal, the SMS side of the phone's store, and a restore of old Spam entries twice (S16).
 * Every number and text is made up.
 */
@RunWith(AndroidJUnit4::class)
class SpamProviderMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: SpamDatabase
    private val before = SpamProviderMigration.moverFor
    private val beforeRequest = SpamProviderMigration.requestPass
    private val beforeAfterPass = SpamProviderMigration.afterPass

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, SpamDatabase::class.java).allowMainThreadQueries().build()
        // The run after a first-run pass would go to the sorting thread; the tests that need it call it themselves.
        SpamProviderMigration.afterPass = {}
        context.getSharedPreferences(PrefsMigrationJournal.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(FirstRunStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        FirstRun.reload(context)
    }

    @After
    fun tearDown() {
        SpamProviderMigration.moverFor = before
        SpamProviderMigration.requestPass = beforeRequest
        SpamProviderMigration.afterPass = beforeAfterPass
        context.getSharedPreferences(FirstRunStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        FirstRun.reload(context)
        db.close()
    }

    private fun held(body: String, at: Long, mark: Long? = null) = BlockedMessageEntity(
        address = SENDER, body = body, subscriptionId = 1, isMms = false, receivedAt = at,
        reason = DecisionReason.UNKNOWN_SENDER, ruleId = null, migratedMsgId = mark,
    )

    // The first-run step

    @Test
    fun theFirstRunStepWaitsWhileUMessageIsNotTheDefaultApp() {
        val world = OlderSpamWorld().apply { sms() }
        world.isDefault = false
        SpamProviderMigration.moverFor = { world.mover() }

        assertThat(SpamProviderMigration.firstRunStep.run(context)).isFalse()

        world.isDefault = true
        assertThat(SpamProviderMigration.firstRunStep.run(context)).isTrue()
        assertThat(world.store).hasSize(1)
    }

    @Test
    fun aRunOutsideTheFirstRunThatMovedNothingAsksForNoPass() {
        val world = OlderSpamWorld()
        SpamProviderMigration.moverFor = { world.mover() }

        assertThat(SpamProviderMigration.runPending(context)).isTrue()
    }

    @Test
    fun aStoppedMigrationLetsTheFirstRunGoOn() {
        val world = OlderSpamWorld()
        repeat(8) { world.sms(body = "Spam $it", at = NOW - it * HOUR) }
        world.failingChecks = 100
        SpamProviderMigration.moverFor = { world.mover() }

        assertThat(SpamProviderMigration.firstRunStep.run(context)).isTrue()
        assertThat(world.reports).containsExactly(OlderSpamMover.STOPPED)
        assertThat(world.blocked).hasSize(8)
    }

    @Test
    fun aFailedWriteLetsTheFirstRunGoOnWithoutStoppingTheMigration() {
        val world = OlderSpamWorld()
        val row = world.sms(sub = -1)
        world.failingWrites = 1
        SpamProviderMigration.moverFor = { world.mover() }

        assertThat(SpamProviderMigration.firstRunStep.run(context)).isTrue()
        assertThat(world.reports).isEmpty()
        assertThat(world.row(row.id).migrateTries).isEqualTo(0)
    }

    // runPending: when it runs and which pass it asks for (F-14)

    private fun firstRun(state: FirstRunState) {
        FirstRunStore(context.getSharedPreferences(FirstRunStore.PREFS, Context.MODE_PRIVATE)).write(state)
        FirstRun.reload(context)
    }

    private fun capturePasses(): MutableList<FirstRunTrigger> {
        val asked = mutableListOf<FirstRunTrigger>()
        SpamProviderMigration.requestPass = { _, trigger -> asked += trigger }
        return asked
    }

    @Test
    fun aRunAfterTheFirstSortAsksForTheHistoryCheckOnceForTheMovedSenders() {
        firstRun(FirstRunState(phase = FirstRunPhase.DONE, sortPublished = true))
        val world = OlderSpamWorld().apply { sms() }
        SpamProviderMigration.moverFor = { world.mover() }
        val asked = capturePasses()

        SpamProviderMigration.runPending(context)
        assertThat(world.journalPassOwed()).isTrue()
        // The pass that was asked for began (reviewer: only then is the check done).
        firstRun(FirstRunState(phase = FirstRunPhase.DONE, sortPublished = true, startedAt = Long.MAX_VALUE / 2))
        SpamProviderMigration.runPending(context)

        assertThat(asked).containsExactly(FirstRunTrigger.MIGRATION_FINISHED)
        assertThat(world.journalPassOwed()).isFalse()
    }

    @Test
    fun anOwedHistoryCheckIsAskedForAgainUntilAPassBegan() {
        firstRun(FirstRunState(phase = FirstRunPhase.DONE, sortPublished = true))
        val world = OlderSpamWorld().apply { sms() }
        SpamProviderMigration.moverFor = { world.mover() }
        val asked = capturePasses()

        SpamProviderMigration.runPending(context)
        SpamProviderMigration.runPending(context)

        assertThat(asked).containsExactly(FirstRunTrigger.MIGRATION_FINISHED, FirstRunTrigger.MIGRATION_FINISHED)
        assertThat(world.journalPassOwed()).isTrue()
    }

    @Test
    fun aHistoryCheckOwedFromAKilledRunIsAskedForOnTheNextStart() {
        firstRun(FirstRunState(phase = FirstRunPhase.DONE, sortPublished = true))
        val world = OlderSpamWorld().apply { sms() }
        world.mover().run()
        SpamProviderMigration.moverFor = { world.mover() }
        val asked = capturePasses()

        SpamProviderMigration.runPending(context)

        assertThat(asked).containsExactly(FirstRunTrigger.MIGRATION_FINISHED)
    }

    @Test
    fun aStaleWaitsStateAsksForAPassWithTheOlderSpamStep() {
        firstRun(FirstRunState(phase = FirstRunPhase.DONE, sortPublished = true, olderSpamWaiting = true))
        val world = OlderSpamWorld().apply { sms() }
        SpamProviderMigration.moverFor = { world.mover() }
        val asked = capturePasses()

        SpamProviderMigration.runPending(context)

        assertThat(asked).containsExactly(FirstRunTrigger.DEFAULT_AGAIN)
    }

    @Test
    fun theFirstRunsOwnStepOwesNoPassAndNotesItsUnreadCountsOnceThePassHasSorted() {
        val world = OlderSpamWorld().apply { sms(unread = true) }
        SpamProviderMigration.moverFor = { world.mover() }
        val afterPass = mutableListOf<Context>()
        SpamProviderMigration.afterPass = { afterPass += it }

        SpamProviderMigration.firstRunStep.run(context)

        assertThat(world.journalPassOwed()).isFalse()
        assertThat(world.unreadNoted).isEmpty()
        assertThat(world.journalPendingUnread()).hasSize(1)
        assertThat(afterPass).hasSize(1)

        // The pass ended (DONE, sorted): the run that follows notes the count and empties the journal.
        firstRun(FirstRunState(phase = FirstRunPhase.DONE, sortPublished = true))
        SpamProviderMigration.runPending(context, announce = false)

        assertThat(world.unreadNoted).containsExactly(world.store.single().id)
        assertThat(world.journalPendingUnread()).isEmpty()
    }

    @Test
    fun noPassIsAskedForBeforeTheFirstSortOrWithoutAnnounce() {
        val world = OlderSpamWorld().apply { sms() }
        SpamProviderMigration.moverFor = { world.mover() }
        val asked = capturePasses()

        SpamProviderMigration.runPending(context)
        assertThat(world.store).isEmpty()
        firstRun(FirstRunState(phase = FirstRunPhase.DONE, sortPublished = true))
        SpamProviderMigration.runPending(context, announce = false)

        assertThat(asked).isEmpty()
        assertThat(world.store).hasSize(1)
    }

    // The marks in spamless.db

    @Test
    fun theMarksAndTriesAreWrittenToTheRealTable() {
        val dao = db.spamDao()
        val id = dao.insertBlocked(held("held", NOW - HOUR))
        val rows = AndroidMigrationRows(context, db)

        assertThat(rows.mark(id, MigrationMark.written(55))).isTrue()
        rows.raiseTries(id)
        assertThat(rows.get(id)!!.migratedMsgId).isEqualTo(-55L)
        assertThat(rows.get(id)!!.migrateTries).isEqualTo(1)

        assertThat(rows.mark(id, 55)).isTrue()
        assertThat(rows.all().single().migratedMsgId).isEqualTo(55L)
        assertThat(rows.mark(id + 99, 1)).isFalse()
    }

    @Test
    fun deletingDoneRowsReturnsTheirKeptFiles() {
        val dao = db.spamDao()
        val a = dao.insertBlocked(held("a", NOW).copy(isMms = true, mediaFile = "file-a"))
        val b = dao.insertBlocked(held("b", NOW))
        val rows = AndroidMigrationRows(context, db)

        assertThat(rows.delete(listOf(a, b))).containsExactly("file-a")
        assertThat(rows.all()).isEmpty()
    }

    @Test
    fun theJournalSurvivesARestartOfTheApp() {
        val prefs = context.getSharedPreferences(PrefsMigrationJournal.PREFS, Context.MODE_PRIVATE)
        PrefsMigrationJournal(prefs).apply {
            attempt = MmsAttempt(rowId = 4, newestBefore = 77, startedAt = NOW, receivedAt = NOW - DAY)
            lastDoneAt = NOW - DAY
            haltedVersion = 12
            pendingRepairs = setOf(3L, 9L)
            passOwed = true
            pendingUnread = setOf("s:5", "m:6")
            setKind(RowKey(4, 40), MigrationJournal.KIND_MMS)
            setKind(RowKey(5, 50), MigrationJournal.KIND_TEXT)
            setKind(RowKey(4, 40), MigrationJournal.KIND_TEXT)
            setStalls(RowKey(4, 40), 2, day = 20_000)
            passRequestedAt = 77
            stallsVersion = 3
        }

        val again = PrefsMigrationJournal(prefs)

        assertThat(again.attempt).isEqualTo(MmsAttempt(4, 77, NOW, NOW - DAY))
        assertThat(again.lastDoneAt).isEqualTo(NOW - DAY)
        assertThat(again.haltedVersion).isEqualTo(12)
        assertThat(again.pendingRepairs).containsExactly(3L, 9L)
        assertThat(again.passOwed).isTrue()
        assertThat(again.pendingUnread).containsExactly("s:5", "m:6")
        assertThat(again.kindOf(RowKey(4, 40))).isEqualTo(MigrationJournal.KIND_TEXT)
        // S2-2: the same id with another arrival is another row.
        assertThat(again.kindOf(RowKey(4, 41))).isNull()
        assertThat(again.stallsOf(RowKey(4, 40))).isEqualTo(2)
        assertThat(again.stallDayOf(RowKey(4, 40))).isEqualTo(20_000L)
        assertThat(again.stallDayOf(RowKey(5, 50))).isEqualTo(-1L)
        assertThat(again.passRequestedAt).isEqualTo(77)
        assertThat(again.stallsVersion).isEqualTo(3)
        again.forget(listOf(RowKey(4, 40)))
        assertThat(again.kindOf(RowKey(4, 40))).isNull()
        assertThat(again.stallsOf(RowKey(4, 40))).isEqualTo(0)
        assertThat(again.kindOf(RowKey(5, 50))).isEqualTo(MigrationJournal.KIND_TEXT)
        again.setStalls(RowKey(5, 50), 1, day = 20_001)
        again.clearStalls()
        assertThat(again.stallsOf(RowKey(5, 50))).isEqualTo(0)
        again.attempt = null
        assertThat(PrefsMigrationJournal(prefs).attempt).isNull()
        assertThat(PrefsMigrationJournal(context.getSharedPreferences("other", Context.MODE_PRIVATE)).haltedVersion)
            .isEqualTo(-1)
    }

    // The SMS side of the phone's store

    @Test
    fun anSmsIsWrittenToTheInboxReadSeenDatedWhenItArrivedOnItsSim() {
        val provider = SmsStore.install()
        val store = AndroidMigrationStore(context)

        val id = store.writeSms(SENDER, "Win a prize", NOW - DAY, 2)

        assertThat(id).isGreaterThan(0L)
        val row = provider.row(id)
        assertThat(row["type"]).isEqualTo("${Telephony.Sms.MESSAGE_TYPE_INBOX}")
        assertThat(row["read"]).isEqualTo("1")
        assertThat(row["seen"]).isEqualTo("1")
        assertThat(row["date"]).isEqualTo("${NOW - DAY}")
        assertThat(row["sub_id"]).isEqualTo("2")
        assertThat(store.findSms(SENDER, "Win a prize", NOW - DAY)).isEqualTo(id)
        assertThat(store.checkSms(id, SENDER, NOW - DAY, "Win a prize".length)).isTrue()
    }

    @Test
    fun theSmsProbeAndVerifyNeedTheExactTimeTextAndSender() {
        SmsStore.install()
        val store = AndroidMigrationStore(context)
        val id = store.writeSms(SENDER, "Win a prize", NOW, 1)

        assertThat(store.findSms(SENDER, "Win a prize", NOW + 1)).isNull()
        assertThat(store.findSms(SENDER, "Win a prize!", NOW)).isNull()
        assertThat(store.findSms("+15550001239", "Win a prize", NOW)).isNull()
        assertThat(store.checkSms(id, SENDER, NOW + 1000, "Win a prize".length)).isFalse()
        assertThat(store.checkSms(id, SENDER, NOW, 3)).isFalse()
        assertThat(store.checkSms(id + 1, SENDER, NOW, "Win a prize".length)).isFalse()
    }

    @Test
    fun aSentTextWithTheSameWordsIsNotTakenForAHeldOne() {
        val provider = SmsStore.install()
        provider.insertRaw(SENDER, "Same words", NOW, Telephony.Sms.MESSAGE_TYPE_SENT)

        assertThat(AndroidMigrationStore(context).findSms(SENDER, "Same words", NOW)).isNull()
    }

    // S16: restore of old Spam entries, twice

    @Test
    fun restoringTheSameOldSpamEntriesTwiceWritesEachMessageOnce() {
        val world = OlderSpamWorld()
        val rows = AndroidMigrationRows(context, db)
        val support = BlockedBackupSupport(db.spamDao(), readMedia = { null }, saveMedia = { null })
        val entry = BlockedBackup(
            address = SENDER, body = "Old spam", receivedAt = NOW - 3 * DAY, reason = "UNKNOWN_SENDER",
        )
        fun migrate() = OlderSpamMover(rows, world.probe, world.writer, world.journal, 1) { world.clock }.run()

        assertThat(support.restore(entry)).isTrue()
        migrate()
        assertThat(support.restore(entry)).isFalse()
        migrate()
        assertThat(world.store).hasSize(1)

        // 30 days later the old copy is gone; the same file restored again finds the message in the store.
        world.clock += 31 * DAY
        OlderSpamMover(rows, world.probe, world.writer, world.journal, 1) { world.clock }.cleanUp()
        assertThat(rows.all()).isEmpty()
        assertThat(support.restore(entry)).isTrue()
        migrate()

        assertThat(world.store).hasSize(1)
        assertThat(rows.all().single().migratedMsgId).isEqualTo(world.store.single().id)
    }

    @Test
    fun aRestoreWhileNotTheDefaultAppWaitsInTheHeldTable() {
        val world = OlderSpamWorld().apply { isDefault = false }
        val rows = AndroidMigrationRows(context, db)
        val support = BlockedBackupSupport(db.spamDao(), readMedia = { null }, saveMedia = { null })
        support.restore(BlockedBackup(address = SENDER, body = "Waits", receivedAt = NOW, reason = "BLOCK_RULE"))

        val result = OlderSpamMover(rows, world.probe, world.writer, world.journal, 1).run()

        assertThat(result.waiting).isTrue()
        assertThat(world.store).isEmpty()
        assertThat(rows.all().single().migratedMsgId).isNull()
    }

    /** The SMS table of the phone's store, as the migration reads and writes it (Robolectric has none). */
    class SmsStore : ContentProvider() {
        private lateinit var db: SQLiteDatabase

        override fun onCreate(): Boolean {
            db = SQLiteDatabase.create(null)
            db.execSQL(
                "CREATE TABLE sms (_id INTEGER PRIMARY KEY AUTOINCREMENT, thread_id INTEGER, address TEXT, " +
                    "subject TEXT, body TEXT, date INTEGER, date_sent INTEGER, type INTEGER, read INTEGER, " +
                    "seen INTEGER, sub_id INTEGER)"
            )
            return true
        }

        fun row(id: Long): Map<String, String?> = db.rawQuery("SELECT * FROM sms WHERE _id = ?", arrayOf("$id")).use {
            it.moveToFirst()
            (0 until it.columnCount).associate { i -> it.getColumnName(i) to it.getString(i) }
        }

        fun insertRaw(address: String, body: String, date: Long, type: Int, threadId: Long = 0): Long =
            db.insert("sms", null, ContentValues().apply {
                put("address", address)
                put("body", body)
                put("date", date)
                put("type", type)
                put("thread_id", threadId)
            })

        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val id = uri.pathSegments.firstOrNull()?.toLongOrNull()
            val sel = listOfNotNull(id?.let { "_id = $it" }, selection?.let { "($it)" }).joinToString(" AND ")
            return db.query(
                "sms", projection?.map { it }?.toTypedArray(), sel.ifEmpty { null },
                selectionArgs?.map { it }?.toTypedArray(), null, null, sortOrder,
            )
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri =
            ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, db.insertOrThrow("sms", null, values))

        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0

        override fun getType(uri: Uri): String? = null

        companion object {
            fun install(): SmsStore =
                Robolectric.setupContentProvider(SmsStore::class.java, Telephony.Sms.CONTENT_URI.authority)
        }
    }
}
