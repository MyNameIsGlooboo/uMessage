package com.glooboo.umessage.testing

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import org.robolectric.Robolectric

/**
 * A small stand-in for the system MMS provider (authority `mms` only; [FakeTelephonyProvider] keeps sms, mms-sms and
 * the block list), for the provider-truth MMS tests (step E). Rows of `pdu` and their `addr` rows in an in-memory
 * SQLite database; selections go straight to SQLite. Understands `content://mms`, `content://mms/inbox`,
 * `content://mms/<id>`, `content://mms/inbox/<id>` and `content://mms/<id>/addr`.
 */
class FakeMmsProvider : ContentProvider() {

    private lateinit var db: SQLiteDatabase

    override fun onCreate(): Boolean {
        db = SQLiteDatabase.create(null)
        db.execSQL(
            "CREATE TABLE pdu (_id INTEGER PRIMARY KEY AUTOINCREMENT, thread_id INTEGER, m_type INTEGER, " +
                "msg_box INTEGER DEFAULT 1, read INTEGER DEFAULT 0, seen INTEGER DEFAULT 0, tr_id TEXT, ct_l TEXT, " +
                "date INTEGER DEFAULT 0, sub_id INTEGER DEFAULT -1, exp INTEGER DEFAULT 0, st INTEGER DEFAULT 0, " +
                // Written by mmslib on a failed download (retrieve status, error type).
                "retr_st INTEGER, err_type INTEGER)"
        )
        db.execSQL(
            "CREATE TABLE addr (_id INTEGER PRIMARY KEY AUTOINCREMENT, msg_id INTEGER, address TEXT, type INTEGER)"
        )
        return true
    }

    /**
     * A notice (notification-ind) of [sender], as mmslib saves it: without a chat it gets mmslib's dummy thread id
     * (Long.MAX_VALUE), as for an automatic download. Returns its `content://mms/inbox/<id>` uri.
     */
    fun addNotice(
        sender: String,
        threadId: Long = Long.MAX_VALUE,
        transactionId: String = "T-1",
        location: String = "http://mmsc.example.com/msg/a",
        read: Boolean = false,
        dateSeconds: Long = 0,
    ): Uri {
        val id = db.insert("pdu", null, ContentValues().apply {
            put("thread_id", threadId)
            put("m_type", NOTIFICATION_IND)
            put("tr_id", transactionId)
            put("ct_l", location)
            put("read", if (read) 1 else 0)
            put("seen", if (read) 1 else 0)
            put("date", dateSeconds)
        })
        db.insert("addr", null, ContentValues().apply {
            put("msg_id", id)
            put("address", sender)
            put("type", FROM)
        })
        return Uri.parse("content://mms/inbox/$id")
    }

    /** A stored MMS in [threadId] (any row that is not a notice). */
    fun addMms(threadId: Long): Long = db.insert("pdu", null, ContentValues().apply {
        put("thread_id", threadId)
        put("m_type", RETRIEVE_CONF)
    })

    /** Column [column] of pdu row [id]; null when the row is gone. */
    fun value(id: Long, column: String): Long? =
        db.rawQuery("SELECT $column FROM pdu WHERE _id = ?", arrayOf(id.toString())).use {
            if (it.moveToFirst()) it.getLong(0) else null
        }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        val target = target(uri) ?: return null
        val where = listOfNotNull(target.idClause, selection?.takeIf { it.isNotBlank() }?.let { "($it)" })
            .joinToString(" AND ").ifEmpty { null }
        val order = sortOrder?.substringBefore(" LIMIT ")
        val limit = sortOrder?.substringAfter(" LIMIT ", "")?.ifEmpty { null }
        return db.query(target.table, projection, where, selectionArgs, null, null, order, limit)
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        val target = target(uri) ?: return 0
        val where = listOfNotNull(target.idClause, selection?.takeIf { it.isNotBlank() }?.let { "($it)" })
            .joinToString(" AND ").ifEmpty { null }
        return db.update(target.table, values, where, selectionArgs)
    }

    /** A pdu row (`content://mms`, `content://mms/inbox`): returns its uri; anything else (parts, addr) is refused. */
    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (target(uri)?.let { it.table == "pdu" && it.idClause == null } != true) return null
        val id = db.insert("pdu", null, ContentValues(values ?: ContentValues()))
        return if (id > 0) Uri.withAppendedPath(uri, id.toString()) else null
    }

    /** Really deletes the matching rows, so a test sees when the library dropped a notice. */
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val target = target(uri) ?: return 0
        val where = listOfNotNull(target.idClause, selection?.takeIf { it.isNotBlank() }?.let { "($it)" })
            .joinToString(" AND ").ifEmpty { null }
        return db.delete(target.table, where, selectionArgs)
    }

    /** Whether pdu row [id] is still there. */
    fun exists(id: Long): Boolean = value(id, "_id") != null

    override fun getType(uri: Uri): String? = null

    private class Target(val table: String, val idClause: String?)

    private fun target(uri: Uri): Target? {
        val segments = uri.pathSegments.filter { it != "inbox" }
        val id = segments.firstOrNull()?.toLongOrNull()
        return when {
            segments.isEmpty() -> Target("pdu", null)
            id != null && segments.size == 1 -> Target("pdu", "_id = $id")
            id != null && segments.getOrNull(1) == "addr" -> Target("addr", "msg_id = $id")
            else -> null
        }
    }

    companion object {
        const val NOTIFICATION_IND = 130
        private const val RETRIEVE_CONF = 132
        private const val FROM = 137

        /** Registers a fresh provider for the `mms` authority. */
        fun install(): FakeMmsProvider =
            Robolectric.buildContentProvider(FakeMmsProvider::class.java)
                .create(ProviderInfo().apply { authority = "mms" })
                .get()
    }
}
