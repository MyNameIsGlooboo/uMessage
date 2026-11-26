package com.glooboo.umessage.testing

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import org.robolectric.Robolectric

/**
 * A stand-in for the telephony provider that is complete enough for the app's thread readers (`getMessages`, `getMMS`,
 * `getThreadSnippet`): SMS rows, MMS rows with their parts and sender addresses, the simple threads table and the
 * canonical addresses. Selections and sort orders go straight to SQLite, so the app's own queries run unchanged.
 *
 * Install with [FakeThreadStore.install] at the start of each test. Dates are as the real provider stores them:
 * SMS in milliseconds, MMS in seconds.
 *
 * The threads table has none of the list's columns, so a chat-list read fails unless [listColumns] is set: then
 * `mms-sms/conversations` is a view with the snippet, date and message count of each thread, read live from the
 * SMS and MMS rows (a thread whose messages were all deleted has a count of 0, as on a phone).
 */
class FakeThreadStore : ContentProvider() {

    private lateinit var db: SQLiteDatabase

    /** Serve the chat list's columns (the threads_list view) instead of the bare threads table. */
    @Volatile var listColumns = false

    /** Give no cursor for MMS parts, like a store that cannot be read. */
    @Volatile var failPartsRead = false

    override fun onCreate(): Boolean {
        db = SQLiteDatabase.create(null)
        db.execSQL(
            "CREATE TABLE sms (_id INTEGER PRIMARY KEY, thread_id INTEGER, address TEXT, body TEXT, date INTEGER, " +
                "type INTEGER, status INTEGER DEFAULT -1, read INTEGER DEFAULT 1, seen INTEGER DEFAULT 1, " +
                "sub_id INTEGER DEFAULT -1)"
        )
        db.execSQL(
            "CREATE TABLE mms (_id INTEGER PRIMARY KEY, thread_id INTEGER, date INTEGER, msg_box INTEGER, " +
                "read INTEGER DEFAULT 1, seen INTEGER DEFAULT 1, sub_id INTEGER DEFAULT -1, st INTEGER DEFAULT 0)"
        )
        db.execSQL("CREATE TABLE part (_id INTEGER PRIMARY KEY AUTOINCREMENT, mid INTEGER, ct TEXT, text TEXT)")
        db.execSQL("CREATE TABLE addr (_id INTEGER PRIMARY KEY AUTOINCREMENT, msg_id INTEGER, address TEXT, type INTEGER)")
        db.execSQL("CREATE TABLE threads (_id INTEGER PRIMARY KEY, recipient_ids TEXT)")
        db.execSQL("CREATE TABLE canonical_addresses (_id INTEGER PRIMARY KEY, address TEXT)")
        db.execSQL(
            "CREATE VIEW threads_list AS SELECT t._id AS _id, t.recipient_ids AS recipient_ids, " +
                "(SELECT body FROM sms s WHERE s.thread_id = t._id ORDER BY s.date DESC LIMIT 1) AS snippet, " +
                "COALESCE((SELECT MAX(s.date) FROM sms s WHERE s.thread_id = t._id), 0) AS date, " +
                "(SELECT COUNT(*) FROM sms s WHERE s.thread_id = t._id) + " +
                "(SELECT COUNT(*) FROM mms m WHERE m.thread_id = t._id) AS message_count, 0 AS archived FROM threads t"
        )
        return true
    }

    /** Creates thread [threadId] with one participant, so MMS rows of it have someone to show. */
    fun addThread(threadId: Long, address: String) {
        db.insert("canonical_addresses", null, ContentValues().apply { put("_id", threadId); put("address", address) })
        db.insert("threads", null, ContentValues().apply { put("_id", threadId); put("recipient_ids", "$threadId") })
    }

    /** Creates group thread [threadId] with [addresses]; their canonical ids start at [firstAddressId]. */
    fun addGroupThread(threadId: Long, addresses: List<String>, firstAddressId: Long = 1000L) {
        val ids = addresses.mapIndexed { index, address ->
            val id = firstAddressId + index
            db.insert("canonical_addresses", null, ContentValues().apply { put("_id", id); put("address", address) })
            id
        }
        db.insert("threads", null, ContentValues().apply {
            put("_id", threadId); put("recipient_ids", ids.joinToString(" "))
        })
    }

    fun addSms(id: Long, threadId: Long, body: String, dateMs: Long, address: String = ADDRESS, type: Int = 1) {
        db.insert("sms", null, ContentValues().apply {
            put("_id", id); put("thread_id", threadId); put("address", address); put("body", body)
            put("date", dateMs); put("type", type)
        })
    }

    /** An MMS with an optional text part and an optional picture. [dateSec] is in seconds, as on a real phone. */
    fun addMms(
        id: Long,
        threadId: Long,
        text: String?,
        dateSec: Long,
        withPicture: Boolean = false,
        box: Int = 1,
        address: String = ADDRESS,
    ) {
        db.insert("mms", null, ContentValues().apply {
            put("_id", id); put("thread_id", threadId); put("date", dateSec); put("msg_box", box)
        })
        if (text != null) {
            db.insert("part", null, ContentValues().apply { put("mid", id); put("ct", "text/plain"); put("text", text) })
        }
        if (withPicture) {
            db.insert("part", null, ContentValues().apply { put("mid", id); put("ct", "image/jpeg") })
        }
        db.insert("addr", null, ContentValues().apply { put("msg_id", id); put("address", address); put("type", 137) })
    }

    fun smsIds(): List<Long> = ids("sms")

    fun mmsIds(): List<Long> = ids("mms")

    private fun ids(table: String): List<Long> = db.rawQuery("SELECT _id FROM $table ORDER BY _id", null).use {
        buildList { while (it.moveToNext()) add(it.getLong(0)) }
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val segments = uri.pathSegments
        var table: String
        var where = selection?.takeIf { it.isNotBlank() }
        var args = selectionArgs?.toList() ?: emptyList()
        fun and(clause: String, arg: String) {
            where = if (where == null) clause else "$clause AND ($where)"
            args = listOf(arg) + args
        }
        when (uri.authority) {
            "sms" -> {
                table = "sms"
                segments.firstOrNull()?.toLongOrNull()?.let { and("_id = ?", it.toString()) }
            }

            "mms" -> when {
                segments.firstOrNull() == "part" -> {
                    if (failPartsRead) return null
                    table = "part"
                }
                segments.size == 2 && segments[1] == "addr" -> {
                    table = "addr"
                    and("msg_id = ?", segments[0])
                }

                else -> {
                    table = "mms"
                    segments.firstOrNull()?.toLongOrNull()?.let { and("_id = ?", it.toString()) }
                }
            }

            "mms-sms" -> table = when (segments.firstOrNull()) {
                "conversations" -> if (listColumns) "threads_list" else "threads"
                "canonical-addresses" -> "canonical_addresses"
                else -> return null
            }

            else -> return null
        }
        // The app appends "LIMIT n" to the sort order, which SQLiteDatabase.query takes as a separate argument.
        val order = sortOrder?.substringBefore(" LIMIT ")
        val limit = sortOrder?.substringAfter(" LIMIT ", "")?.ifEmpty { null }
        return db.query(table, projection, where, args.toTypedArray(), null, null, order, limit)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val table = when (uri.authority) {
            "sms" -> "sms"
            "mms" -> "mms"
            else -> return 0
        }
        return db.delete(table, selection, selectionArgs)
    }

    override fun getType(uri: Uri): String? = null

    companion object {
        const val ADDRESS = "+15550001111"

        fun install(): FakeThreadStore =
            Robolectric.buildContentProvider(FakeThreadStore::class.java)
                .create(ProviderInfo().apply { authority = "sms;mms;mms-sms" })
                .get()
    }
}
