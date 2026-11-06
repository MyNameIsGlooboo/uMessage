package com.glooboo.umessage.testing

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import org.robolectric.Robolectric
import java.util.concurrent.atomic.AtomicInteger

/**
 * A telephony provider big enough to measure the conversation list: SMS rows, the threads table the list reads
 * (snippet, date, recipients, message count, archived), canonical addresses and the system block list. Every query
 * is counted, with the rows it returned, so tests can compare how much of the store a refresh reads.
 *
 * Install with [CountingInboxProvider.install]. SMS dates are in milliseconds, thread dates too, as on a phone.
 */
class CountingInboxProvider : ContentProvider() {

    private lateinit var db: SQLiteDatabase

    /** Queries by table ("sms", "mms", "threads", "canonical_addresses", "blocked"). */
    val queries = HashMap<String, AtomicInteger>()

    /** Rows returned by every query together. */
    val rows = AtomicInteger()

    /** Runs before each threads query; a test can block here to hold a refresh in the middle. */
    @Volatile var beforeThreadsQuery: (() -> Unit)? = null

    override fun onCreate(): Boolean {
        db = SQLiteDatabase.create(null)
        db.execSQL(
            "CREATE TABLE sms (_id INTEGER PRIMARY KEY, thread_id INTEGER, address TEXT, body TEXT, date INTEGER, " +
                "type INTEGER DEFAULT 1, status INTEGER DEFAULT -1, read INTEGER DEFAULT 1, seen INTEGER DEFAULT 1, " +
                "sub_id INTEGER DEFAULT -1)"
        )
        db.execSQL("CREATE INDEX sms_thread ON sms(thread_id, date)")
        db.execSQL(
            "CREATE TABLE mms (_id INTEGER PRIMARY KEY, thread_id INTEGER, date INTEGER, msg_box INTEGER, " +
                "read INTEGER DEFAULT 1, seen INTEGER DEFAULT 1, sub_id INTEGER DEFAULT -1)"
        )
        db.execSQL(
            "CREATE TABLE threads (_id INTEGER PRIMARY KEY, snippet TEXT, date INTEGER, recipient_ids TEXT, " +
                "message_count INTEGER DEFAULT 0, archived INTEGER DEFAULT 0)"
        )
        db.execSQL("CREATE TABLE canonical_addresses (_id INTEGER PRIMARY KEY, address TEXT)")
        db.execSQL("CREATE TABLE blocked (_id INTEGER PRIMARY KEY, original_number TEXT, e164_number TEXT)")
        return true
    }

    /** [threads] chats of [messagesPerThread] SMS each; thread i is with "+1555000" + i, newest thread last. */
    fun seed(threads: Int, messagesPerThread: Int, subIdOf: (thread: Long) -> Int = { -1 }) {
        db.beginTransaction()
        try {
            var id = 1L
            for (t in 1..threads.toLong()) {
                repeat(messagesPerThread) {
                    insertSms(id, t, "Message $id", dateMs = BASE_MS + id * 1000, subId = subIdOf(t))
                    id++
                }
                db.insert("canonical_addresses", null, ContentValues().apply { put("_id", t); put("address", address(t)) })
                db.insert("threads", null, ContentValues().apply {
                    put("_id", t); put("snippet", "Message ${id - 1}"); put("date", BASE_MS + (id - 1) * 1000)
                    put("recipient_ids", "$t"); put("message_count", messagesPerThread)
                })
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** A new SMS in [thread], as the phone stores it: the threads row follows. */
    fun receive(id: Long, thread: Long, body: String, dateMs: Long, subId: Int = -1) {
        insertSms(id, thread, body, dateMs, subId)
        db.execSQL(
            "UPDATE threads SET snippet = ?, date = ?, message_count = message_count + 1 WHERE _id = ?",
            arrayOf<Any>(body, dateMs, thread)
        )
    }

    /** A first SMS from a new number: a new thread with one message. */
    fun addThread(thread: Long, smsId: Long, body: String, dateMs: Long) {
        db.insert("canonical_addresses", null, ContentValues().apply { put("_id", thread); put("address", address(thread)) })
        db.insert("threads", null, ContentValues().apply {
            put("_id", thread); put("snippet", ""); put("date", 0); put("recipient_ids", "$thread")
        })
        receive(smsId, thread, body, dateMs)
    }

    fun deleteThread(thread: Long) {
        db.delete("sms", "thread_id = ?", arrayOf(thread.toString()))
        db.delete("threads", "_id = ?", arrayOf(thread.toString()))
    }

    private fun insertSms(id: Long, thread: Long, body: String, dateMs: Long, subId: Int) {
        db.insert("sms", null, ContentValues().apply {
            put("_id", id); put("thread_id", thread); put("address", address(thread)); put("body", body)
            put("date", dateMs); put("sub_id", subId)
        })
    }

    fun resetCounts() {
        synchronized(queries) { queries.clear() }
        rows.set(0)
    }

    fun queryCount(table: String): Int = synchronized(queries) { queries[table]?.get() ?: 0 }

    fun totalQueries(): Int = synchronized(queries) { queries.values.sumOf { it.get() } }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val segments = uri.pathSegments
        var where = selection?.takeIf { it.isNotBlank() }
        var args = selectionArgs?.toList() ?: emptyList()
        val table = when (uri.authority) {
            "sms" -> "sms"
            "mms" -> if (segments.isEmpty()) "mms" else return null
            "mms-sms" -> when (segments.firstOrNull()) {
                "conversations" -> "threads"
                "canonical-addresses" -> "canonical_addresses"
                else -> return null
            }

            "com.android.blockednumber" -> "blocked"
            else -> return null
        }
        if (table == "sms") {
            segments.firstOrNull()?.toLongOrNull()?.let {
                where = if (where == null) "_id = ?" else "_id = ? AND ($where)"
                args = listOf(it.toString()) + args
            }
        }
        if (table == "threads") beforeThreadsQuery?.invoke()
        synchronized(queries) { queries.getOrPut(table) { AtomicInteger() }.incrementAndGet() }
        // The app appends "LIMIT n" to the sort order, which SQLiteDatabase.query takes as a separate argument.
        val order = sortOrder?.substringBefore(" LIMIT ")
        val limit = sortOrder?.substringAfter(" LIMIT ", "")?.ifEmpty { null }
        val cursor = db.query(table, projection, where, args.toTypedArray(), null, null, order, limit)
        rows.addAndGet(cursor.count)
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun getType(uri: Uri): String? = null

    companion object {
        /** Seeded message dates start here (Nov 2023), in milliseconds as on a phone. */
        const val BASE_MS = 1_700_000_000_000L

        fun address(thread: Long) = "+1555000" + thread.toString().padStart(4, '0')

        fun install(): CountingInboxProvider =
            Robolectric.buildContentProvider(CountingInboxProvider::class.java)
                .create(ProviderInfo().apply { authority = "sms;mms;mms-sms;com.android.blockednumber" })
                .get()
    }
}
