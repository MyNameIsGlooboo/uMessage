package com.glooboo.umessage.spam.data

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.isStoredLate
import com.glooboo.umessage.helpers.NotifyContext
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric

/**
 * A restored ("Not spam") or late-delivered SMS must date its chat by when it arrived. The provider stamps a thread
 * with the insert time, so the chat list first showed the restore time and later, once the provider recomputed the
 * thread, the real one.
 */
@RunWith(AndroidJUnit4::class)
class RestoredThreadDateTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @After
    fun tearDown() = resetSharedMessagesDatabase()

    @Test
    fun aLateStoredSms_leavesItsThreadDatedByWhenItArrived(): Unit = offMainThread {
        val provider = StampingProvider.install()
        val arrived = System.currentTimeMillis() - 2 * 60 * 1000

        context.deliverIncomingSms(
            address = "+15550002222", subject = "", body = "Not spam after all", date = arrived,
            subscriptionId = -1, status = Telephony.Sms.STATUS_NONE, notify = NotifyContext.NONE,
        )

        assertThat(provider.threadDates().values).containsExactly(arrived)
    }

    @Test
    fun onlyMessagesStoredNoticeablyLateNeedTheRecompute() {
        assertThat(isStoredLate(dateMillis = 10_000, nowMillis = 10_400)).isFalse()
        assertThat(isStoredLate(dateMillis = 10_000, nowMillis = 130_000)).isTrue()
    }

    /**
     * Behaves like the AOSP telephony provider where it matters here: an SMS insert stamps its thread with the time
     * of the insert, and a delete on `conversations/<id>` (even one that matches nothing) recomputes the thread's
     * date from its newest message.
     */
    class StampingProvider : ContentProvider() {
        private lateinit var db: SQLiteDatabase

        override fun onCreate(): Boolean {
            db = SQLiteDatabase.create(null)
            db.execSQL(
                "CREATE TABLE sms (_id INTEGER PRIMARY KEY AUTOINCREMENT, address TEXT, subject TEXT, body TEXT, " +
                    "date INTEGER, read INTEGER, thread_id INTEGER, type INTEGER, sub_id INTEGER)"
            )
            db.execSQL("CREATE TABLE threads (_id INTEGER PRIMARY KEY, date INTEGER)")
            return true
        }

        fun threadDates(): Map<Long, Long> = db.rawQuery("SELECT _id, date FROM threads", null).use {
            buildMap { while (it.moveToNext()) put(it.getLong(0), it.getLong(1)) }
        }

        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? = when {
            uri.authority == "mms-sms" && uri.pathSegments.firstOrNull() == "threadID" ->
                MatrixCursor(arrayOf("_id")).apply { addRow(arrayOf(THREAD)) }

            else -> null
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            if (uri.authority != "sms") return null
            val id = db.insert("sms", null, values)
            val threadId = values?.getAsLong("thread_id") ?: return null
            db.execSQL("INSERT OR REPLACE INTO threads (_id, date) VALUES (?, ?)", arrayOf(threadId, System.currentTimeMillis()))
            return ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, id)
        }

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            if (uri.authority != "mms-sms" || uri.pathSegments.firstOrNull() != "conversations") return 0
            val threadId = uri.pathSegments.getOrNull(1)?.toLongOrNull() ?: return 0
            val where = "thread_id = $threadId" + (selection?.let { " AND ($it)" } ?: "")
            val deleted = db.delete("sms", where, selectionArgs)
            db.execSQL(
                "UPDATE threads SET date = (SELECT MAX(date) FROM sms WHERE thread_id = ?) WHERE _id = ?",
                arrayOf(threadId, threadId)
            )
            return deleted
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

        override fun getType(uri: Uri): String? = null

        companion object {
            const val THREAD = 42L

            fun install(): StampingProvider = Robolectric.buildContentProvider(StampingProvider::class.java)
                .create(ProviderInfo().apply { authority = "sms;mms-sms" })
                .get()
        }
    }
}
