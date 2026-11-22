package com.glooboo.umessage.spam.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FirstRun
import com.glooboo.umessage.spam.FirstRunPhase
import com.glooboo.umessage.spam.FirstRunState
import com.glooboo.umessage.spam.FirstRunStore
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.engine.NumberNormalizer
import com.glooboo.umessage.spam.engine.SpamEngine
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.ui.spam.OlderSpam
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Step F hand-over 1 (blocking): with provider truth no purge ever deletes a held row by age, also with auto-delete
 * days set; the purge moves waiting rows and runs auto-delete on the phone's store instead. Also: the migration
 * outside a pass waits for the first sort, a late MMS result reopens a "nothing to move" row, and the older-spam list shows rows that are written but not verified. Numbers are made up.
 */
@RunWith(AndroidJUnit4::class)
class PurgeKeepsHeldRowsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var db: SpamDatabase
    private lateinit var settings: SpamSettingsStore
    private val swept = mutableListOf<Set<String>>()
    private val beforeMover = SpamProviderMigration.moverFor
    private val beforeSource = SpamAutoDelete.sourceFor

    private val platform = object : SpamPlatform {
        override fun notifyBlocked(message: BlockedMessage) = Unit
        override fun deliverRestored(pending: PendingSmsEntity): Long? = null
        override fun sweepMedia(keep: Set<String>) {
            swept += keep
        }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(app, SpamDatabase::class.java).allowMainThreadQueries().build()
        app.getSharedPreferences("spamless_spam_settings", Context.MODE_PRIVATE).edit().clear().commit()
        settings = SpamSettingsStore(app)
        resetSpamEngine()
        firstRun(FirstRunState())
    }

    @After
    fun tearDown() {
        SpamProviderMigration.moverFor = beforeMover
        SpamAutoDelete.sourceFor = beforeSource
        db.close()
        app.getSharedPreferences(FirstRunStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        FirstRun.reload(app)
        val field = SpamDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }
        (field.get(null) as? SpamDatabase)?.let { runCatching { it.close() } }
        field.set(null, null)
        resetSpamEngine()
    }

    /** SpamEngine keeps singletons made on the shared database; they are made again for the next test. */
    private fun resetSpamEngine() {
        listOf("repository", "filter", "settingsStore", "forwardPlanner", "checker").forEach { name ->
            runCatching { SpamEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.set(null, null) }
        }
    }

    private fun firstRun(state: FirstRunState) {
        FirstRunStore(app.getSharedPreferences(FirstRunStore.PREFS, Context.MODE_PRIVATE)).write(state)
        FirstRun.reload(app)
    }

    private fun repo() = DefaultSpamRepository(platform, db, settings, NumberNormalizer { "US" })

    private fun oldRow(mark: Long? = null) = db.spamDao().insertBlocked(
        BlockedMessageEntity(
            address = "+15550001234", body = "Old held text", subscriptionId = 1, isMms = false, receivedAt = 1_000L,
            reason = DecisionReason.UNKNOWN_SENDER, ruleId = null, migratedMsgId = mark,
        )
    )

    /** Auto-delete set to 7 days after the one-time mapping (the days read 0 before it). */
    private fun autoDeleteDays(days: Int) {
        settings.applyProviderTruthMapping()
        settings.update { it.copy(purgeAfterDays = days) }
        assertThat(settings.get().purgeAfterDays).isEqualTo(days)
    }

    @Test
    fun thePurgeDeletesNoHeldRowEvenWithAutoDeleteDays() = runTest {
        autoDeleteDays(7)
        val waiting = oldRow()
        val written = oldRow(mark = MigrationMark.written(40))
        val moved = oldRow(mark = 41)

        repo().purgeExpired()

        assertThat(db.spamDao().getAllBlocked().map { it.id }).containsExactly(waiting, written, moved)
        // The sweep of files no row names still runs.
        assertThat(swept).hasSize(1)
    }

    @Test
    fun theEnginePurgeMovesWaitingRowsAndRunsAutoDelete() {
        firstRun(FirstRunState(phase = FirstRunPhase.DONE, sortPublished = true))
        var movers = 0
        var autoDeletes = 0
        SpamProviderMigration.moverFor = {
            movers++
            OlderSpamWorld().mover()
        }
        SpamAutoDelete.sourceFor = {
            autoDeletes++
            NoSpam
        }
        autoDeleteDays(7)

        offMainThread { SpamEngine.purgeExpired(app, now = System.currentTimeMillis()) }

        assertThat(movers).isEqualTo(1)
        assertThat(autoDeletes).isEqualTo(1)
    }

    @Test
    fun outsideAPassTheMigrationWaitsForTheFirstSort() {
        var movers = 0
        SpamProviderMigration.moverFor = {
            movers++
            OlderSpamWorld().mover()
        }

        SpamProviderMigration.runPending(app)
        firstRun(FirstRunState(phase = FirstRunPhase.HISTORY, sortPublished = true))
        SpamProviderMigration.runPending(app)
        assertThat(movers).isEqualTo(0)

        firstRun(FirstRunState(phase = FirstRunPhase.DONE, sortPublished = true))
        SpamProviderMigration.runPending(app, announce = false)
        assertThat(movers).isEqualTo(1)
    }

    @Test
    fun aLateMmsResultReopensANothingToMoveRow() {
        val rows = AndroidMigrationRows(app, db)
        val nothing = oldRow(mark = MigrationMark.NOTHING_TO_MOVE)
        val moved = oldRow(mark = 77)

        rows.reopenNothingToMove(nothing)
        rows.reopenNothingToMove(moved)

        assertThat(rows.get(nothing)!!.migratedMsgId).isNull()
        assertThat(rows.get(moved)!!.migratedMsgId).isEqualTo(77L)
    }

    @Test
    fun theOlderSpamListShowsRowsThatAreWrittenButNotVerified() {
        fun row(id: Long, mark: Long?) = BlockedMessage(
            id = id, address = "+15550001234", body = "b$id", subscriptionId = 1, isMms = false, receivedAt = id,
            reason = DecisionReason.UNKNOWN_SENDER, migratedMsgId = mark,
        )
        val rows = listOf(row(1, null), row(2, MigrationMark.written(9)), row(3, 9), row(4, 0))

        assertThat(OlderSpam.of(rows).map { it.id }).containsExactly(2L, 1L).inOrder()
        assertThat(OlderSpam.count(rows)).isEqualTo(2)
    }

    private object NoSpam : AutoDeleteSource {
        override fun canDelete() = true
        override fun spamThreads() = emptyList<SpamThread>()
        override fun cached(threadId: Long) = emptyList<Message>()
        override fun isKept(message: Message) = false
        override fun storeMatches(message: Message, thread: SpamThread) = false
        override fun delete(message: Message) = false
        override fun afterThread(threadId: Long) = Unit
    }
}
