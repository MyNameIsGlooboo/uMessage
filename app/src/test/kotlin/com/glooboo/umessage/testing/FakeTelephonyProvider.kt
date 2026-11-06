package com.glooboo.umessage.testing

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.Telephony
import org.robolectric.Robolectric

/**
 * A small stand-in for the system SMS provider, the thread-id lookup and the system block list, so receive-path code
 * can run under Robolectric (which has none of them). Backed by an in-memory SQLite database; selections are passed
 * straight to SQLite, so the app's own queries work unchanged.
 *
 * Install with [FakeTelephonyProvider.install] at the start of each test.
 */
class FakeTelephonyProvider : ContentProvider() {

    private lateinit var db: SQLiteDatabase

    /** While true every SMS insert fails the way a busy or broken provider does (insert returns null). */
    @Volatile var failInserts = false

    /** How many SMS inserts were attempted, failed ones included. */
    @Volatile var insertAttempts = 0

    /** How many times something tried to add a number to the system block list. */
    @Volatile var systemBlockInserts = 0

    private val threadIds = HashMap<String, Long>()

    override fun onCreate(): Boolean {
        db = SQLiteDatabase.create(null)
        db.execSQL(
            "CREATE TABLE sms (_id INTEGER PRIMARY KEY AUTOINCREMENT, address TEXT, subject TEXT, body TEXT, " +
                "date INTEGER, date_sent INTEGER DEFAULT 0, read INTEGER DEFAULT 0, seen INTEGER DEFAULT 0, " +
                "thread_id INTEGER, type INTEGER, sub_id INTEGER DEFAULT -1, status INTEGER DEFAULT -1, " +
                "person INTEGER, protocol INTEGER, reply_path_present INTEGER, service_center TEXT, locked INTEGER, " +
                "error_code INTEGER)"
        )
        db.execSQL("CREATE TABLE blocked (_id INTEGER PRIMARY KEY AUTOINCREMENT, original_number TEXT, e164_number TEXT)")
        return true
    }

    /** Every SMS row, oldest first. */
    fun smsRows(): List<Map<String, Any?>> {
        db.rawQuery("SELECT * FROM sms ORDER BY _id", null).use { c ->
            val rows = ArrayList<Map<String, Any?>>()
            while (c.moveToNext()) {
                rows += (0 until c.columnCount).associate { i ->
                    c.getColumnName(i) to when (c.getType(i)) {
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        Cursor.FIELD_TYPE_NULL -> null
                        else -> c.getString(i)
                    }
                }
            }
            return rows
        }
    }

    fun addSms(address: String, body: String, dateMs: Long, threadId: Long, type: Int = Telephony.Sms.MESSAGE_TYPE_INBOX) {
        db.insert("sms", null, ContentValues().apply {
            put("address", address)
            put("body", body)
            put("date", dateMs)
            put("thread_id", threadId)
            put("type", type)
            put("read", 1)
        })
    }

    fun blockNumber(number: String) {
        db.insert("blocked", null, ContentValues().apply {
            put("original_number", number)
            put("e164_number", number)
        })
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = when (uri.authority) {
        SMS -> {
            val box = uri.pathSegments.firstOrNull()
            val boxSelection = when (box) {
                "inbox" -> "type = ${Telephony.Sms.MESSAGE_TYPE_INBOX}"
                "sent" -> "type = ${Telephony.Sms.MESSAGE_TYPE_SENT}"
                else -> null
            }
            val where = listOfNotNull(boxSelection, selection?.takeIf { it.isNotBlank() }?.let { "($it)" })
                .joinToString(" AND ").ifEmpty { null }
            // The app appends "LIMIT n" to the sort order, which SQLiteDatabase.query takes as a separate argument.
            val order = sortOrder?.substringBefore(" LIMIT ")
            val limit = sortOrder?.substringAfter(" LIMIT ", "")?.ifEmpty { null }
            db.query("sms", projection, where, selectionArgs, null, null, order, limit)
        }

        MMS_SMS -> if (uri.pathSegments.firstOrNull() == "threadID") {
            val recipients = uri.getQueryParameters("recipient").sorted().joinToString(" ")
            val id = synchronized(threadIds) { threadIds.getOrPut(recipients) { threadIds.size + 1L } }
            MatrixCursor(arrayOf("_id")).apply { addRow(arrayOf(id)) }
        } else {
            null
        }

        BLOCKED -> db.query("blocked", projection, selection, selectionArgs, null, null, sortOrder)
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        if (uri.authority == BLOCKED) {
            systemBlockInserts++
            return null
        }
        if (uri.authority != SMS) return null
        insertAttempts++
        if (failInserts) return null
        val id = db.insert("sms", null, ContentValues(values).apply {
            if (!containsKey("type")) put("type", Telephony.Sms.MESSAGE_TYPE_INBOX)
        })
        return if (id > 0) Uri.withAppendedPath(Telephony.Sms.CONTENT_URI, id.toString()) else null
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        if (uri.authority == SMS) db.update("sms", values, selection, selectionArgs) else 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        if (uri.authority == SMS) db.delete("sms", selection, selectionArgs) else 0

    override fun getType(uri: Uri): String? = null

    companion object {
        private const val SMS = "sms"
        private const val MMS_SMS = "mms-sms"
        private const val BLOCKED = "com.android.blockednumber"

        /** Registers a fresh provider for the sms, mms-sms and blocked-number authorities. */
        fun install(): FakeTelephonyProvider =
            Robolectric.buildContentProvider(FakeTelephonyProvider::class.java)
                .create(ProviderInfo().apply { authority = "$SMS;$MMS_SMS;$BLOCKED" })
                .get()
    }
}
