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
import com.glooboo.umessage.testing.offMainThread
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
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

            /** What is in the store: row id to SIM, without the deleted rows. */
            fun left(): Map<Long, Int?> = rows.indices.filter { it + 1L !in deleted }
                .associate { it + 1L to rows[it].getAsInteger(Telephony.Sms.SUBSCRIPTION_ID) }
        }
    }

    @Before
    fun setUp() {
        HidingSmsStore.rows.clear()
        HidingSmsStore.deleted.clear()
        Robolectric.setupContentProvider(HidingSmsStore::class.java, "sms")
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
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
    fun theStepsDropAnInactiveSimAndKeepAPresentOrUnknownOne() {
        val written = mutableListOf<Int>()
        val id = MissingSim.writeReadably(2, { false }, { written += it; 7L }, { true }, { true })
        assertThat(id).isEqualTo(7L)
        assertThat(written).containsExactly(-1)

        written.clear()
        MissingSim.writeReadably(2, { true }, { written += it; 8L }, { true }, { true })
        assertThat(written).containsExactly(2)

        written.clear()
        MissingSim.writeReadably(2, { null }, { written += it; 9L }, { true }, { true })
        assertThat(written).containsExactly(2)
    }

    @Test
    fun anMmsTheStoreHidesIsDeletedByItsIdAndTheSecondOneIsReturned() {
        val gone = mutableListOf<Long>()
        var next = 10L

        val id = MissingSim.writeReadably(2, { true }, { next++ }, { it != 10L }, { gone += it; true })

        assertThat(gone).containsExactly(10L)
        assertThat(id).isEqualTo(11L)
    }

    @Test
    fun aRowStillHiddenWithoutASimIsDeletedAndTheWriteFails() {
        val gone = mutableListOf<Long>()
        var next = 10L

        assertThrows(IllegalStateException::class.java) {
            MissingSim.writeReadably(2, { true }, { next++ }, { false }, { gone += it; true })
        }

        assertThat(gone).containsExactly(10L, 11L)
    }

    @Test
    fun aHiddenRowThatCannotBeDeletedFailsTheWriteWithoutAnotherRow() {
        var writes = 0

        assertThrows(IllegalStateException::class.java) {
            MissingSim.writeReadably(2, { true }, { writes++; 10L }, { false }, { false })
        }

        assertThat(writes).isEqualTo(1)
    }
}
