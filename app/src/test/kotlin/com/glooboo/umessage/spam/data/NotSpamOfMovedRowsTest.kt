package com.glooboo.umessage.spam.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.MmsMedia
import com.glooboo.umessage.spam.RestoreOutcome
import com.glooboo.umessage.spam.engine.NumberNormalizer
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * F-1: with provider truth the old "Not spam" (the old Blocked screen, the decision log) never writes a held row the
 * message migration took (written or done): its message is in the phone's store already. "Not spam" and the
 * migration share one lock. Every number is made up.
 */
@RunWith(AndroidJUnit4::class)
class NotSpamOfMovedRowsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var db: SpamDatabase
    private val deliveredMms = mutableListOf<BlockedMessage>()

    private val platform = object : SpamPlatform {
        override fun notifyBlocked(message: BlockedMessage) = Unit
        override fun deliverRestored(pending: PendingSmsEntity): Long? = 500L
        override fun deliverRestoredMms(message: BlockedMessage): Long? = 600L.also { deliveredMms += message }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(app, SpamDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun repo() = DefaultSpamRepository(platform, db, SpamSettingsStore(app), NumberNormalizer { "US" })

    private fun keptMms(mark: Long?) = db.spamDao().insertBlocked(
        BlockedMessageEntity(
            address = "+15550001234", body = "A picture", subscriptionId = 1, isMms = true, receivedAt = 1_000L,
            reason = DecisionReason.UNKNOWN_SENDER, ruleId = null, media = MmsMedia.STORED,
            mediaFile = "0f9c2a3e-1111-2222-3333-444455556666", migratedMsgId = mark,
        )
    )

    @Test
    fun aMovedOrWrittenRowIsNotRestoredAgain() = runBlocking<Unit> {
        val done = keptMms(mark = 77)
        val written = keptMms(mark = MigrationMark.written(78))

        assertThat(repo().restore(done).outcome).isEqualTo(RestoreOutcome.NOT_FOUND)
        assertThat(repo().restore(written).outcome).isEqualTo(RestoreOutcome.NOT_FOUND)

        assertThat(deliveredMms).isEmpty()
        assertThat(db.spamDao().getAllBlocked().map { it.id }).containsExactly(done, written)
    }

    @Test
    fun aRowNotTakenYetIsRestoredAsBefore() = runBlocking<Unit> {
        val held = keptMms(mark = null)

        assertThat(repo().restore(held).outcome).isEqualTo(RestoreOutcome.RESTORED)
        assertThat(deliveredMms).hasSize(1)
    }

    @Test
    fun notSpamWaitsWhileAMigrationRunHoldsTheLock() = runBlocking<Unit> {
        val held = keptMms(mark = null)
        val locked = CountDownLatch(1)
        val release = CountDownLatch(1)
        val migration = Thread {
            synchronized(SpamProviderMigration.lock) {
                locked.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }.apply { start() }
        locked.await(10, TimeUnit.SECONDS)

        val restore = async(Dispatchers.IO) { repo().restore(held) }
        Thread.sleep(300)
        assertThat(restore.isCompleted).isFalse()
        assertThat(deliveredMms).isEmpty()

        release.countDown()
        assertThat(restore.await().outcome).isEqualTo(RestoreOutcome.RESTORED)
        migration.join(10_000)
    }
}
