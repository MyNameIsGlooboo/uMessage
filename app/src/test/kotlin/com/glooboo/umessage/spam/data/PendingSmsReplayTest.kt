package com.glooboo.umessage.spam.data

import android.app.Application
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.NotifyContext
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.SpamFilter
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.spam.engine.NumberNormalizer
import com.glooboo.umessage.spam.engine.SpamEngine
import com.glooboo.umessage.testing.offMainThread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric

/**
 * SE-41: the write-ahead path every incoming SMS takes (pending row, filter, blocked store or inbox, replay).
 * The rule under test: a message is removed from pending_sms only once it is stored somewhere.
 */
@RunWith(AndroidJUnit4::class)
class PendingSmsReplayTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    // Wall-clock time: storeBlocked purges blocked messages older than the retention period by the real clock.
    private val now = System.currentTimeMillis()

    /** A minimal system SMS provider: inserts are kept in memory, the inbox query answers from them. */
    class FakeSmsProvider : ContentProvider() {
        override fun onCreate() = true

        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            if (failInserts) return null
            inserted += ContentValues(values)
            return ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, inserted.size.toLong())
        }

        override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor {
            val cursor = MatrixCursor(arrayOf(Telephony.Sms._ID))
            inserted.forEachIndexed { i, row ->
                val hit = args == null || (row.getAsString(Telephony.Sms.ADDRESS) == args[0] &&
                    row.getAsString(Telephony.Sms.BODY) == args[1] && row.getAsLong(Telephony.Sms.DATE).toString() == args[2])
                if (hit) cursor.addRow(arrayOf<Any>(i + 1L))
            }
            return cursor
        }

        override fun getType(uri: Uri): String? = null
        override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0

        companion object {
            val inserted = mutableListOf<ContentValues>()
            @Volatile var failInserts = false
        }
    }

    private class NoopPlatform : SpamPlatform {
        override fun notifyBlocked(message: BlockedMessage) = Unit
        override fun deliverRestored(pending: PendingSmsEntity): Long? = 1L
    }

    private var decide: (IncomingMessage) -> FilterDecision = { FilterDecision.Allow(DecisionReason.MODE_OFF) }
    private var repositoryBroken = false

    @Before fun setUp() {
        resetDatabaseSingleton()
        clearLeases()
        FakeSmsProvider.inserted.clear()
        FakeSmsProvider.failInserts = false
        Robolectric.setupContentProvider(FakeSmsProvider::class.java, "sms")
        UMessage.filterFactory = {
            object : SpamFilter {
                override fun evaluate(message: IncomingMessage) = decide(message)
            }
        }
        UMessage.repositoryFactory = {
            check(!repositoryBroken) { "database unavailable" }
            DefaultSpamRepository(NoopPlatform(), SpamDatabase.getInstance(it), SpamSettingsStore(it), NumberNormalizer { "GB" })
        }
    }

    @After fun tearDown() {
        UMessage.filterFactory = null
        UMessage.repositoryFactory = null
        resetDatabaseSingleton()
    }

    /** SpamDatabase keeps one instance per process; each test gets a fresh one on its own Robolectric files dir. */
    private fun resetDatabaseSingleton() {
        val field = SpamDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }
        (field.get(null) as SpamDatabase?)?.close()
        field.set(null, null)
    }

    /** Row ids restart at 1 in every fresh database, so leases left by another test must not carry over. */
    private fun clearLeases() {
        val field = PendingSmsQueue::class.java.getDeclaredField("leases").apply { isAccessible = true }
        (field.get(PendingSmsQueue) as MutableMap<*, *>).clear()
    }

    private val dao get() = SpamDatabase.getInstance(app).spamDao()

    private fun pending(body: String = "Dinner at 8?", createdAt: Long = now - 60_000L) = PendingSmsEntity(
        address = "+447121234567", subject = "", body = body, date = createdAt - 1_000L,
        subscriptionId = 1, status = Telephony.Sms.STATUS_NONE, createdAt = createdAt,
    )

    private fun addPending(row: PendingSmsEntity): Long = offMainThread { dao.insertPending(row) }

    private fun replay() = offMainThread { PendingSmsQueue.replay(app, now) }

    @Test fun allowedRowGoesToTheInboxAndLeavesTheQueue() {
        addPending(pending())
        replay()
        assertEquals(1, FakeSmsProvider.inserted.size)
        assertEquals("Dinner at 8?", FakeSmsProvider.inserted.single().getAsString(Telephony.Sms.BODY))
        assertTrue(offMainThread { dao.getPending() }.isEmpty())
    }

    @Test fun blockedRowIsStoredAsBlockedAndLeavesTheQueue() {
        decide = { FilterDecision.Block(DecisionReason.UNKNOWN_SENDER) }
        addPending(pending(body = "You won a prize"))
        replay()
        assertTrue(FakeSmsProvider.inserted.isEmpty())
        assertEquals("You won a prize", offMainThread { dao.getAllBlocked() }.single().body)
        assertTrue(offMainThread { dao.getPending() }.isEmpty())
    }

    @Test fun rowStaysQueuedWhenTheInboxInsertFails() {
        FakeSmsProvider.failInserts = true
        addPending(pending())
        replay()
        assertEquals(1, offMainThread { dao.getPending() }.size)

        // The next replay delivers it once the provider works again.
        FakeSmsProvider.failInserts = false
        replay()
        assertEquals(1, FakeSmsProvider.inserted.size)
        assertTrue(offMainThread { dao.getPending() }.isEmpty())
    }

    @Test fun filterCrashDeliversTheMessage() {
        decide = { throw IllegalStateException("filter broke") }
        addPending(pending())
        replay()
        assertEquals(1, FakeSmsProvider.inserted.size)
        assertTrue(offMainThread { dao.getPending() }.isEmpty())
    }

    @Test fun blockedMessageIsDeliveredWhenTheBlockedStoreFails() {
        decide = { FilterDecision.Block(DecisionReason.UNKNOWN_SENDER) }
        repositoryBroken = true
        addPending(pending())
        replay()
        assertEquals(1, FakeSmsProvider.inserted.size)
        assertTrue(offMainThread { dao.getPending() }.isEmpty())
    }

    @Test fun rowAlreadyInTheInboxIsNotInsertedTwice() {
        val row = pending()
        addPending(row)
        offMainThread { IncomingSmsProcessor.deliver(app, row, NotifyContext.NONE) }
        replay()
        assertEquals(1, FakeSmsProvider.inserted.size)
        assertTrue(offMainThread { dao.getPending() }.isEmpty())
    }

    @Test fun freshRowIsLeftForTheReceiverThatOwnsIt() {
        addPending(pending(createdAt = now - 1_000L))
        replay()
        assertTrue(FakeSmsProvider.inserted.isEmpty())
        assertEquals(1, offMainThread { dao.getPending() }.size)
    }

    @Test fun expiredRowSkipsTheFilterButIsNotDeliveredIfAlreadyBlocked() {
        decide = { FilterDecision.Block(DecisionReason.UNKNOWN_SENDER) }
        val old = pending(body = "Old spam", createdAt = now - PendingSmsPolicy.MAX_AGE_MS - 60_000L)
        val incoming = IncomingMessage(old.address, old.body, old.subscriptionId, receivedAt = old.date)
        assertTrue(offMainThread { SpamEngine.storeBlocked(app, incoming, FilterDecision.Block(DecisionReason.UNKNOWN_SENDER)) })
        addPending(old)

        replay()
        assertTrue(FakeSmsProvider.inserted.isEmpty())
        assertEquals(1, offMainThread { dao.getAllBlocked() }.size)
        assertTrue(offMainThread { dao.getPending() }.isEmpty())
    }

    @Test fun expiredRowIsDeliveredEvenIfTheFilterWouldBlockIt() {
        decide = { FilterDecision.Block(DecisionReason.UNKNOWN_SENDER) }
        addPending(pending(createdAt = now - PendingSmsPolicy.MAX_AGE_MS - 60_000L))
        replay()
        assertEquals(1, FakeSmsProvider.inserted.size)
        assertTrue(offMainThread { dao.getPending() }.isEmpty())
    }

    @Test fun storeBlockedReportsFailureSoTheCallerDelivers() {
        repositoryBroken = true
        val incoming = IncomingMessage("+447121234567", "hi", 1)
        assertFalse(SpamEngine.storeBlocked(app, incoming, FilterDecision.Block(DecisionReason.UNKNOWN_SENDER)))
    }
}
