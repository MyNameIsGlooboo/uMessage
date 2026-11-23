package com.glooboo.umessage.spam.data

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.Telephony
import android.telephony.SubscriptionManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.SpamFilter
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.testing.offMainThread
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSubscriptionManager.SubscriptionInfoBuilder

/**
 * J18: a held message that goes back to the inbox ("Not spam", restore) is never left hidden by a store that takes an
 * insert for a SIM that is gone and then hides the row. The SMS path runs on a store that does that; the MMS path is
 * mmslib's persist step, so its steps are checked through the shared helper. Every number and text is made up.
 */
@RunWith(AndroidJUnit4::class)
class MissingSimTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    /** An SMS store that accepts every insert and hides the rows of [hidingSim] from every query. */
    class HidingSmsStore : ContentProvider() {
        override fun onCreate() = true

        override fun insert(uri: Uri, values: ContentValues?): Uri {
            rows += ContentValues(values)
            return ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, rows.size.toLong())
        }

        private fun visible(index: Int) =
            rows[index].getAsInteger(Telephony.Sms.SUBSCRIPTION_ID) != hidingSim && index.toLong() + 1 !in deleted

        override fun query(
            uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?,
        ): Cursor {
            val cursor = MatrixCursor(arrayOf(Telephony.Sms._ID))
            val wanted = uri.lastPathSegment?.toLongOrNull()
            check(!(wanted != null && byIdThrows)) { "store cannot be asked" }
            rows.indices.filter(::visible).forEach { i ->
                val hit = when {
                    wanted != null -> wanted == i + 1L
                    args == null -> true
                    else -> rows[i].getAsString(Telephony.Sms.ADDRESS) == args[0] &&
                        rows[i].getAsString(Telephony.Sms.BODY) == args[1]
                }
                if (hit) cursor.addRow(arrayOf<Any>(i + 1L))
            }
            return cursor
        }

        /** A delete by id reaches a hidden row too (the worst case for the clean-up is a store that refuses it). */
        override fun delete(uri: Uri, selection: String?, args: Array<String>?): Int {
            val id = uri.lastPathSegment?.toLongOrNull() ?: return 0
            return if (id in 1..rows.size && deleted.add(id)) 1 else 0
        }

        override fun getType(uri: Uri): String? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0

        companion object {
            val rows = mutableListOf<ContentValues>()
            val deleted = mutableSetOf<Long>()
            const val hidingSim = 2

            /** A read of one row by its id fails (the store cannot be asked). */
            var byIdThrows = false

            /** What is in the store: row id to SIM, without the deleted rows. */
            fun left(): Map<Long, Int?> = rows.indices.filter { it + 1L !in deleted }
                .associate { it + 1L to rows[it].getAsInteger(Telephony.Sms.SUBSCRIPTION_ID) }
        }
    }

    @Before
    fun setUp() {
        HidingSmsStore.rows.clear()
        HidingSmsStore.deleted.clear()
        HidingSmsStore.byIdThrows = false
        resetDatabaseSingleton()
        clearLeases()
        Robolectric.setupContentProvider(HidingSmsStore::class.java, "sms")
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
    }

    @After
    fun tearDown() = resetDatabaseSingleton()

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

    private fun activeSims(vararg ids: Int) {
        val infos = ids.map {
            SubscriptionInfoBuilder.newBuilder().setId(it).setSimSlotIndex(it - 1).buildSubscriptionInfo()
        }
        val manager = shadowOf(app.getSystemService(SubscriptionManager::class.java))
        manager.setActiveSubscriptionInfos(*infos.toTypedArray())
    }

    private fun held(sim: Int) = PendingSmsEntity(
        address = "+15550001234", subject = "", body = "Dinner at 8?", date = 1_000L, subscriptionId = sim,
        status = Telephony.Sms.STATUS_NONE, createdAt = 2_000L,
    )

    private fun restore(sim: Int): Long? = offMainThread { AndroidSpamPlatform(app).deliverRestored(held(sim)) }

    @Test
    fun aRestoredSmsOfAMissingSimIsWrittenOnceWithoutIt() {
        activeSims(1)

        val id = restore(sim = 2)

        assertThat(HidingSmsStore.rows).hasSize(1)
        assertThat(HidingSmsStore.left()).containsExactly(id, -1)
    }

    @Test
    fun aRestoredSmsThatTheStoreHidesIsDeletedAndUndoGetsTheFinalRow() {
        // The SIM is active as far as the phone says, yet the store hides the row (the API 34 case of the report).
        activeSims(1, 2)

        val id = restore(sim = 2)

        assertThat(HidingSmsStore.rows).hasSize(2)
        assertThat(id).isEqualTo(2L)
        assertThat(HidingSmsStore.left()).containsExactly(2L, -1)
    }

    @Test
    fun aRestoredSmsOnAPresentSimKeepsItsSim() {
        activeSims(1, 2)

        val id = restore(sim = 1)

        assertThat(HidingSmsStore.left()).containsExactly(id, 1)
    }

    @Test
    fun aRestoredSmsIsWrittenWithoutTheSimWhenThePhoneStatePermissionIsDenied() {
        // No grant and no SIM list: the phone cannot tell which SIM is active, so the SIM is dropped.
        shadowOf(app).denyPermissions(Manifest.permission.READ_PHONE_STATE)

        val id = restore(sim = 2)

        assertThat(HidingSmsStore.rows).hasSize(1)
        assertThat(HidingSmsStore.left()).containsExactly(id, -1)
    }

    @Test
    fun aRestoredSmsWhoseReadBackCannotBeAnsweredFailsAndLeavesNoCopyBehind() {
        activeSims(1, 2)
        HidingSmsStore.byIdThrows = true

        val id = restore(sim = 2)

        // The restore fails (the held copy stays); the row just written is taken out again, no double is made.
        assertThat(id).isNull()
        assertThat(HidingSmsStore.left()).isEmpty()
    }

    @Test
    fun aQueuedNotSpamIsReplayedWithoutAMissingSimAndNothingIsLeftHidden() {
        activeSims(1, 2)
        val now = System.currentTimeMillis()
        val row = held(sim = 2).copy(approved = true, createdAt = now - 60_000L)
        offMainThread { SpamDatabase.getInstance(app).spamDao().insertPending(row) }

        offMainThread { PendingSmsQueue.replay(app, now) }

        assertThat(HidingSmsStore.left().values).containsExactly(-1)
        assertThat(offMainThread { SpamDatabase.getInstance(app).spamDao().getPending() }).isEmpty()
    }

    @Test
    fun anMmsTheStoreHidesIsMadeVisibleInPlaceByAnUpdateOfItsSimAndNothingIsWrittenAgain() {
        Robolectric.setupContentProvider(HidingMmsStore::class.java, "mms")
        HidingMmsStore.rows.clear()
        activeSims(1, 2)
        var writes = 0

        val id = MissingSim.writeReadably(app, subscriptionId = 2, isMms = true) { sim ->
            writes++
            val values = ContentValues().apply { put(Telephony.Mms.SUBSCRIPTION_ID, sim) }
            ContentUris.parseId(app.contentResolver.insert(Telephony.Mms.CONTENT_URI, values)!!)
        }

        // Android 14: a delete does not reach a hidden MMS, an update by id does; the same row is the final one.
        assertThat(writes).isEqualTo(1)
        assertThat(id).isEqualTo(1L)
        assertThat(HidingMmsStore.rows.map { it.getAsInteger(Telephony.Mms.SUBSCRIPTION_ID) }).containsExactly(-1)
    }

    @Test
    fun anMmsWhoseSimUpdateIsRefusedGetsAVisibleCopyWithoutTheSim() {
        Robolectric.setupContentProvider(HidingMmsStore::class.java, "mms")
        HidingMmsStore.rows.clear()
        HidingMmsStore.refuseUpdates = true
        activeSims(1, 2)
        val sims = mutableListOf<Int>()

        val id = try {
            MissingSim.writeReadably(app, subscriptionId = 2, isMms = true) { sim ->
                sims += sim
                val values = ContentValues().apply { put(Telephony.Mms.SUBSCRIPTION_ID, sim) }
                ContentUris.parseId(app.contentResolver.insert(Telephony.Mms.CONTENT_URI, values)!!)
            }
        } finally {
            HidingMmsStore.refuseUpdates = false
        }

        // The hidden row cannot be deleted either: it stays, the message is visible once.
        assertThat(sims).containsExactly(2, -1).inOrder()
        assertThat(id).isEqualTo(2L)
        assertThat(MissingSim.readable(app, 2L, isMms = true)).isTrue()
        assertThat(MissingSim.readable(app, 1L, isMms = true)).isFalse()
    }

    @Test
    fun aQueuedTextThatIsNotRestoredIsReplayedWithoutASimThatIsNotActive() {
        activeSims(1)
        UMessage.filterFactory = {
            object : SpamFilter {
                override fun evaluate(message: IncomingMessage) = FilterDecision.Allow(DecisionReason.MODE_OFF)
            }
        }
        try {
            val now = System.currentTimeMillis()
            val row = held(sim = 2).copy(createdAt = now - 60_000L)
            offMainThread { SpamDatabase.getInstance(app).spamDao().insertPending(row) }

            offMainThread { PendingSmsQueue.replay(app, now) }

            assertThat(HidingSmsStore.left().values).containsExactly(-1)
        } finally {
            UMessage.filterFactory = null
        }
    }

    @Test
    fun anMmsWriteThatThrewOnASimIsNotTriedAgainBecauseItMayHaveLeftAHalfRow() {
        val written = mutableListOf<Int>()

        assertThrows(IllegalStateException::class.java) {
            MissingSim.writeReadably(2, MissingSim.Ops(retryAfterError = false)) { written += it; error("cut off") }
        }

        assertThat(written).containsExactly(2)
    }

    @Test
    fun theStepsDropASimThatIsNotKnownToBeActive() {
        val written = mutableListOf<Int>()
        val write = { sim: Int -> written += sim; 7L }

        assertThat(MissingSim.writeReadably(2, MissingSim.Ops(active = { false }), write)).isEqualTo(7L)
        assertThat(written).containsExactly(-1)

        written.clear()
        MissingSim.writeReadably(2, MissingSim.Ops(active = { null }), write)
        assertThat(written).containsExactly(-1)

        written.clear()
        MissingSim.writeReadably(2, MissingSim.Ops(active = { error("no list") }), write)
        assertThat(written).containsExactly(-1)

        written.clear()
        MissingSim.writeReadably(2, MissingSim.Ops(active = { true }), write)
        assertThat(written).containsExactly(2)
    }

    @Test
    fun aWriteRefusedOnASimIsTriedOnceWithoutIt() {
        val written = mutableListOf<Int>()

        val refused = MissingSim.writeReadably(2, MissingSim.Ops()) { written += it; if (it == 2) 0L else 5L }
        assertThat(refused).isEqualTo(5L)
        assertThat(written).containsExactly(2, -1).inOrder()

        written.clear()
        val thrown = MissingSim.writeReadably(2, MissingSim.Ops()) { written += it; if (it == 2) error("no") else 6L }
        assertThat(thrown).isEqualTo(6L)
        assertThat(written).containsExactly(2, -1).inOrder()
    }

    @Test
    fun aHiddenRowIsMadeVisibleInPlaceWhenTheStoreAllowsIt() {
        var writes = 0
        val refreshed = mutableListOf<Long>()
        val ops = MissingSim.Ops(readable = { it != 10L }, makeVisible = { true }, refresh = { refreshed += it })

        val id = MissingSim.writeReadably(2, ops) { writes++; 10L }

        assertThat(id).isEqualTo(10L)
        assertThat(writes).isEqualTo(1)
        assertThat(refreshed).containsExactly(10L)
    }

    @Test
    fun aHiddenRowIsDeletedAndWrittenAgainAndTheNewIdIsReturned() {
        val gone = mutableListOf<Long>()
        val refreshed = mutableListOf<Long>()
        var next = 10L
        val ops = MissingSim.Ops(readable = { it != 10L }, delete = { gone += it; true }, refresh = { refreshed += it })

        val id = MissingSim.writeReadably(2, ops) { next++ }

        assertThat(gone).containsExactly(10L)
        assertThat(id).isEqualTo(11L)
        assertThat(refreshed).containsExactly(11L)
    }

    @Test
    fun whenNothingMakesAHiddenRowRightTheVisibleCopyIsWrittenAllTheSame() {
        var next = 10L
        val ops = MissingSim.Ops(readable = { it != 10L }, delete = { false })

        val id = MissingSim.writeReadably(2, ops) { next++ }

        assertThat(id).isEqualTo(11L)
    }

    @Test
    fun aReadBackThatCannotBeAnsweredFailsTheWriteAndTakesTheRowOutAgain() {
        val gone = mutableListOf<Long>()
        var writes = 0

        assertThrows(IllegalStateException::class.java) {
            MissingSim.writeReadably(2, MissingSim.Ops(readable = { null }, delete = { gone += it; true })) {
                writes++
                10L
            }
        }

        assertThat(writes).isEqualTo(1)
        assertThat(gone).containsExactly(10L)
    }

    @Test
    fun aRowStillHiddenWithoutASimIsDeletedAndTheWriteFails() {
        val gone = mutableListOf<Long>()
        var next = 10L

        assertThrows(IllegalStateException::class.java) {
            MissingSim.writeReadably(2, MissingSim.Ops(readable = { false }, delete = { gone += it; true })) { next++ }
        }

        assertThat(gone).containsExactly(10L, 11L)
    }

    /** An MMS store that hides the rows of SIM 2: a delete does not reach them, an update by id does. */
    class HidingMmsStore : ContentProvider() {
        override fun onCreate() = true

        override fun insert(uri: Uri, values: ContentValues?): Uri {
            rows += ContentValues(values)
            return ContentUris.withAppendedId(Telephony.Mms.CONTENT_URI, rows.size.toLong())
        }

        private fun hidden(index: Int) = rows[index].getAsInteger(Telephony.Mms.SUBSCRIPTION_ID) == 2

        override fun query(
            uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?,
        ): Cursor {
            val cursor = MatrixCursor(arrayOf(Telephony.Mms._ID))
            val wanted = uri.lastPathSegment?.toLongOrNull()
            rows.indices.filter { !hidden(it) && (wanted == null || wanted == it + 1L) }
                .forEach { cursor.addRow(arrayOf<Any>(it + 1L)) }
            return cursor
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?): Int {
            val id = uri.lastPathSegment?.toLongOrNull() ?: return 0
            if (refuseUpdates || id !in 1..rows.size) return 0
            rows[(id - 1).toInt()].putAll(values ?: return 0)
            return 1
        }

        override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
        override fun getType(uri: Uri): String? = null

        companion object {
            val rows = mutableListOf<ContentValues>()
            var refuseUpdates = false
        }
    }
}
