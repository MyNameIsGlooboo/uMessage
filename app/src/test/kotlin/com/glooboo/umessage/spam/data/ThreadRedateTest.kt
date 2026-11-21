package com.glooboo.umessage.spam.data

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric

/**
 * A migrated or restored MMS must leave its thread in the phone's store dated by its newest message. On API 35 the
 * provider's recompute skips a thread without an SMS, so 0 of 40 migrated MMS threads were right on the emulator.
 */
@RunWith(AndroidJUnit4::class)
class ThreadRedateTest {

    private val resolver: ContentResolver = ApplicationProvider.getApplicationContext<Context>().contentResolver

    @Test
    fun anMmsOnlyThreadIsDatedByItsMmsAndKeepsNoHelperDraft() {
        val store = Api35Store.install()
        val thread = store.mms(dateSeconds = ARRIVED_S, read = 1)

        ThreadRedate.redate(resolver, thread)

        assertThat(store.thread(thread)).isEqualTo(Triple(ARRIVED_S * 1000, 1, 1))
        assertThat(store.smsCount()).isEqualTo(0)
    }

    @Test
    fun anUnreadMmsKeepsItsThreadUnread() {
        val store = Api35Store.install()
        val thread = store.mms(dateSeconds = ARRIVED_S, read = 0)

        ThreadRedate.redate(resolver, thread)

        assertThat(store.thread(thread)).isEqualTo(Triple(ARRIVED_S * 1000, 1, 0))
    }

    @Test
    fun aThreadWithAnSmsNeedsNoHelper() {
        val store = Api35Store.install()
        val thread = store.mms(dateSeconds = ARRIVED_S, read = 1)
        store.sms(thread, date = ARRIVED_S * 1000 - 5_000)
        val inserts = store.smsInserts

        ThreadRedate.redate(resolver, thread)

        assertThat(store.thread(thread).first).isEqualTo(ARRIVED_S * 1000)
        assertThat(store.smsInserts).isEqualTo(inserts)
    }

    @Test
    fun aDraftLeftByARunThatDiedIsDeleted() {
        val store = Api35Store.install()
        val thread = store.mms(dateSeconds = ARRIVED_S, read = 1)
        store.sms(thread, date = 0, type = Telephony.Sms.MESSAGE_TYPE_DRAFT, body = "")

        ThreadRedate.redate(resolver, thread)

        assertThat(store.smsCount()).isEqualTo(0)
        assertThat(store.thread(thread).first).isEqualTo(ARRIVED_S * 1000)
    }

    /**
     * The API 35 telephony provider where it matters here: inserts and MMS date updates stamp the thread "now" (its
     * triggers), an SMS insert sets the thread's read state from its SMS only, a read update on an MMS from its MMS,
     * and the recompute (`conversations/<id>` delete) only visits a thread that holds an SMS.
     */
    class Api35Store : ContentProvider() {
        private lateinit var db: SQLiteDatabase
        var smsInserts = 0

        override fun onCreate(): Boolean {
            db = SQLiteDatabase.create(null)
            db.execSQL(
                "CREATE TABLE sms (_id INTEGER PRIMARY KEY AUTOINCREMENT, thread_id INTEGER, type INTEGER, " +
                    "date INTEGER, body TEXT, address TEXT, read INTEGER DEFAULT 0, seen INTEGER DEFAULT 0)"
            )
            db.execSQL(
                "CREATE TABLE pdu (_id INTEGER PRIMARY KEY AUTOINCREMENT, thread_id INTEGER, date INTEGER, " +
                    "read INTEGER)"
            )
            db.execSQL(
                "CREATE TABLE threads (_id INTEGER PRIMARY KEY, date INTEGER, message_count INTEGER, read INTEGER)"
            )
            db.execSQL(
                "CREATE TRIGGER sms_insert AFTER INSERT ON sms BEGIN UPDATE threads SET date = $NOW, " +
                    "read = CASE (SELECT COUNT(*) FROM sms WHERE read = 0 AND thread_id = new.thread_id) " +
                    "WHEN 0 THEN 1 ELSE 0 END WHERE _id = new.thread_id; END"
            )
            db.execSQL(
                "CREATE TRIGGER pdu_read AFTER UPDATE OF read ON pdu BEGIN UPDATE threads SET " +
                    "read = CASE (SELECT COUNT(*) FROM pdu WHERE read = 0 AND thread_id = new.thread_id) " +
                    "WHEN 0 THEN 1 ELSE 0 END WHERE _id = new.thread_id; END"
            )
            return true
        }

        /** A thread holding one MMS dated [dateSeconds], stamped "now" as the provider leaves it. Its id. */
        fun mms(dateSeconds: Long, read: Int): Long {
            val thread = db.insert("threads", null, ContentValues().apply {
                put("date", NOW_MS)
                put("message_count", 1)
                put("read", read)
            })
            db.insert("pdu", null, ContentValues().apply {
                put("thread_id", thread)
                put("date", dateSeconds)
                put("read", read)
            })
            return thread
        }

        fun sms(thread: Long, date: Long, type: Int = Telephony.Sms.MESSAGE_TYPE_INBOX, body: String = "Hi") {
            db.insert("sms", null, ContentValues().apply {
                put("thread_id", thread)
                put("type", type)
                put("date", date)
                put("body", body)
                put("read", 1)
            })
        }

        fun thread(id: Long): Triple<Long, Int, Int> =
            db.rawQuery("SELECT date, message_count, read FROM threads WHERE _id = ?", arrayOf("$id")).use {
                it.moveToFirst()
                Triple(it.getLong(0), it.getInt(1), it.getInt(2))
            }

        fun smsCount(): Int = db.rawQuery("SELECT COUNT(*) FROM sms", null).use { it.moveToFirst(); it.getInt(0) }

        private fun table(uri: Uri) = if (uri.authority == "sms") "sms" else "pdu"

        private fun where(uri: Uri, selection: String?): String? {
            val id = uri.pathSegments.firstOrNull()?.toLongOrNull()
            return listOfNotNull(id?.let { "_id = $it" }, selection?.let { "($it)" }).joinToString(" AND ")
                .ifEmpty { null }
        }

        private fun args(args: Array<out String>?) = args?.map { it }?.toTypedArray()

        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val table = if (uri.authority == "mms-sms") "threads" else table(uri)
            return db.query(
                table, projection?.map { it }?.toTypedArray(), selection, args(selectionArgs), null, null, sortOrder,
            )
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? {
            if (uri.authority != "sms") return null
            smsInserts++
            return ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, db.insert("sms", null, values))
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) =
            db.update(table(uri), values, where(uri, selection), args(selectionArgs))

        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
            if (uri.authority != "mms-sms") return db.delete(table(uri), where(uri, selection), args(selectionArgs))
            val thread = uri.pathSegments.getOrNull(1)?.toLongOrNull() ?: return 0
            // MmsSmsDatabaseHelper.updateThreads on API 35: "... WHERE EXISTS (SELECT _id FROM sms WHERE ...)".
            db.execSQL(
                "UPDATE threads SET date = (SELECT MAX(d) FROM (SELECT date * 1000 AS d FROM pdu " +
                    "WHERE thread_id = ?1 UNION SELECT date FROM sms WHERE thread_id = ?1)), message_count = " +
                    "(SELECT COUNT(*) FROM sms WHERE thread_id = ?1 AND type != 3) + " +
                    "(SELECT COUNT(*) FROM pdu WHERE thread_id = ?1) " +
                    "WHERE _id = ?1 AND EXISTS (SELECT _id FROM sms WHERE thread_id = ?1)",
                arrayOf(thread),
            )
            return 0
        }

        override fun getType(uri: Uri): String? = null

        companion object {
            const val NOW_MS = 1_795_233_692_000L
            const val NOW = "$NOW_MS"

            fun install(): Api35Store = Robolectric.buildContentProvider(Api35Store::class.java)
                .create(ProviderInfo().apply { authority = "sms;mms;mms-sms" })
                .get()
        }
    }

    private companion object {
        const val ARRIVED_S = 1_794_982_862L
    }
}
