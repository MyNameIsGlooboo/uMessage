package com.glooboo.umessage.testing

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.robolectric.Robolectric
import java.io.File

/**
 * A stand-in for the whole telephony store that backup and restore touch: `content://sms`, `content://mms` with its
 * parts (attachment bytes kept in files, like the real provider) and addresses, and `content://mms-sms` for thread
 * lookup, the thread list and canonical addresses. Backed by in-memory SQLite, so the app's selections run unchanged.
 *
 * Thread lookup matches numbers loosely on their last ten digits, the way the system does, so "+447121234567" and
 * "07121234567" are one person and share a thread. A set of recipients maps to one thread, whatever its order.
 *
 * Install with [install] at the start of each test.
 */
class FakeMessageStore : ContentProvider() {

    private lateinit var db: SQLiteDatabase
    private lateinit var partDir: File

    /** Part ids whose attachment bytes fail to write, like a full disk or a provider that refuses the file. */
    val failingPartWrites = mutableSetOf<String>()

    /** While true, writing any attachment's bytes fails. */
    @Volatile var failAllPartWrites = false

    /** Part ids whose bytes cannot be read back, like a file the provider lost. */
    val unreadableParts = mutableSetOf<Long>()

    /** How many times a query reached this provider. */
    @Volatile var queries = 0

    override fun onCreate(): Boolean {
        db = SQLiteDatabase.create(null)
        partDir = File(context!!.cacheDir, "fake-parts-${System.nanoTime()}").apply { mkdirs() }
        db.execSQL(
            "CREATE TABLE sms (_id INTEGER PRIMARY KEY AUTOINCREMENT, thread_id INTEGER, address TEXT, body TEXT, " +
                "date INTEGER, date_sent INTEGER DEFAULT 0, type INTEGER, status INTEGER DEFAULT -1, read INTEGER, " +
                "seen INTEGER, sub_id INTEGER DEFAULT -1, locked INTEGER DEFAULT 0, protocol INTEGER, " +
                "service_center TEXT, subject TEXT, person INTEGER, reply_path_present INTEGER, error_code INTEGER)"
        )
        db.execSQL(
            "CREATE TABLE pdu (_id INTEGER PRIMARY KEY AUTOINCREMENT, thread_id INTEGER, date INTEGER, " +
                "date_sent INTEGER DEFAULT 0, msg_box INTEGER, read INTEGER DEFAULT 0, seen INTEGER DEFAULT 0, " +
                "m_type INTEGER, sub TEXT, sub_cs INTEGER, ct_t TEXT, tr_id TEXT, sub_id INTEGER DEFAULT -1, " +
                "locked INTEGER DEFAULT 0, st INTEGER, d_rpt INTEGER, rr INTEGER, text_only INTEGER DEFAULT 0, " +
                "creator TEXT, m_id TEXT)"
        )
        db.execSQL(
            "CREATE TABLE part (_id INTEGER PRIMARY KEY AUTOINCREMENT, mid INTEGER, seq INTEGER DEFAULT 0, ct TEXT, " +
                "name TEXT, chset INTEGER, cd TEXT, fn TEXT, cid TEXT, cl TEXT, ctt_s INTEGER, ctt_t TEXT, " +
                "_data TEXT, text TEXT)"
        )
        db.execSQL(
            "CREATE TABLE addr (_id INTEGER PRIMARY KEY AUTOINCREMENT, msg_id INTEGER, contact_id INTEGER, " +
                "address TEXT, type INTEGER, charset INTEGER)"
        )
        db.execSQL("CREATE TABLE canonical_addresses (_id INTEGER PRIMARY KEY AUTOINCREMENT, address TEXT)")
        db.execSQL("CREATE TABLE threads (_id INTEGER PRIMARY KEY AUTOINCREMENT, recipient_ids TEXT, date INTEGER DEFAULT 0)")
        db.execSQL(
            "CREATE VIEW threads_view AS SELECT t._id AS _id, t.recipient_ids AS recipient_ids, t.date AS date, " +
                "(SELECT count(*) FROM sms WHERE sms.thread_id = t._id) + " +
                "(SELECT count(*) FROM pdu WHERE pdu.thread_id = t._id) AS message_count FROM threads t"
        )
        return true
    }

    // ---- assertions and setup -----------------------------------------------------------------------------------

    fun rows(table: String, orderBy: String = "_id"): List<Map<String, Any?>> =
        db.rawQuery("SELECT * FROM $table ORDER BY $orderBy", null).use { it.toMaps() }

    fun count(table: String, where: String = "1", vararg args: String): Int =
        db.rawQuery("SELECT count(*) FROM $table WHERE $where", args.map { it }.toTypedArray()).use {
            it.moveToFirst(); it.getInt(0)
        }

    /** The bytes stored for a part, or null if none were written. */
    fun partBytes(partId: Long): ByteArray? {
        val path = db.rawQuery("SELECT _data FROM part WHERE _id = ?", arrayOf(partId.toString())).use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: return null
        return File(path).takeIf { it.exists() && it.length() > 0 }?.readBytes()
    }

    /** The phone numbers of a thread, in canonical-address order. */
    fun threadRecipients(threadId: Long): List<String> {
        val ids = db.rawQuery("SELECT recipient_ids FROM threads WHERE _id = ?", arrayOf(threadId.toString())).use {
            if (it.moveToFirst()) it.getString(0) else ""
        }
        return ids.split(" ").filter { it.isNotBlank() }.map { id ->
            db.rawQuery("SELECT address FROM canonical_addresses WHERE _id = ?", arrayOf(id)).use {
                it.moveToFirst(); it.getString(0)
            }
        }
    }

    fun threadIdFor(vararg recipients: String): Long = threadId(recipients.toList())

    fun addSms(address: String, body: String?, date: Long, type: Int = 1, threadId: Long = threadIdFor(address)): Long =
        db.insert("sms", null, ContentValues().apply {
            put("thread_id", threadId); put("address", address); put("body", body); put("date", date)
            put("date_sent", date); put("type", type); put("read", 1)
        })

    /** Adds an MMS with one text part and, when [image] is given, one image part. Returns the message id. */
    fun addMms(
        threadId: Long, dateSeconds: Long, box: Int, text: String?, image: ByteArray?,
        addresses: List<Pair<String, Int>>,
    ): Long {
        val id = db.insert("pdu", null, ContentValues().apply {
            put("thread_id", threadId); put("date", dateSeconds); put("date_sent", 0); put("msg_box", box)
            put("m_type", if (box == 1) 132 else 128); put("read", 1); put("ct_t", "application/vnd.wap.multipart.related")
        })
        if (text != null) {
            db.insert("part", null, ContentValues().apply {
                put("mid", id); put("seq", 0); put("ct", "text/plain"); put("text", text); put("chset", 106)
            })
        }
        if (image != null) {
            val partId = db.insert("part", null, ContentValues().apply {
                put("mid", id); put("seq", 0); put("ct", "image/jpeg"); put("name", "photo.jpg"); put("cl", "photo.jpg")
            })
            val file = File(partDir, "part-$partId").apply { writeBytes(image) }
            db.execSQL("UPDATE part SET _data = ? WHERE _id = ?", arrayOf(file.path, partId))
        }
        addresses.forEach { (address, type) ->
            db.insert("addr", null, ContentValues().apply {
                put("msg_id", id); put("address", address); put("type", type); put("charset", 106)
            })
        }
        return id
    }

    // ---- ContentProvider ----------------------------------------------------------------------------------------

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        queries++
        val segments = uri.pathSegments
        return when (uri.authority) {
            SMS -> {
                val (sel, args) = withId(segments.firstOrNull()?.toLongOrNull(), selection, selectionArgs)
                db.query("sms", projection, sel, args, null, null, sortOrder)
            }

            MMS -> when {
                segments.isEmpty() -> db.query("pdu", projection, selection, selectionArgs, null, null, sortOrder)
                segments[0] == "part" -> {
                    val id = segments.getOrNull(1)?.toLongOrNull()
                    val (sel, args) = withId(id, partSelection(selection), selectionArgs)
                    db.query("part", partProjection(projection), sel, args, null, null, sortOrder)
                }

                segments.size == 1 -> {
                    val (sel, args) = withId(segments[0].toLong(), selection, selectionArgs)
                    db.query("pdu", projection, sel, args, null, null, sortOrder)
                }

                segments[1] == "part" -> {
                    val sel = listOfNotNull("mid = ${segments[0].toLong()}", partSelection(selection)?.let { "($it)" })
                        .joinToString(" AND ")
                    db.query("part", partProjection(projection), sel, selectionArgs?.toArgs(), null, null, sortOrder)
                }

                segments[1] == "addr" -> {
                    val sel = listOfNotNull("msg_id = ${segments[0].toLong()}", selection?.takeIf { it.isNotBlank() }?.let { "($it)" })
                        .joinToString(" AND ")
                    db.query("addr", projection, sel, selectionArgs?.toArgs(), null, null, sortOrder)
                }

                else -> null
            }

            MMS_SMS -> when (segments.firstOrNull()) {
                "threadID" -> MatrixCursor(arrayOf("_id")).apply {
                    addRow(arrayOf(threadId(uri.getQueryParameters("recipient"))))
                }

                "conversations" -> db.query("threads_view", projection, selection, selectionArgs?.toArgs(), null, null, sortOrder)
                "canonical-addresses" -> db.query("canonical_addresses", projection, selection, selectionArgs?.toArgs(), null, null, sortOrder)
                else -> null
            }

            else -> null
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val segments = uri.pathSegments
        val v = ContentValues(values ?: ContentValues())
        return when (uri.authority) {
            SMS -> {
                if (!v.containsKey("type")) v.put("type", 1)
                val id = db.insert("sms", null, v)
                if (id > 0) Uri.parse("content://sms/$id") else null
            }

            MMS -> when {
                segments.isEmpty() -> {
                    val id = db.insert("pdu", null, v)
                    if (id > 0) Uri.parse("content://mms/$id") else null
                }

                segments.size >= 2 && segments[1] == "part" -> {
                    v.put("mid", segments[0].toLong())
                    val id = db.insert("part", null, v)
                    val file = File(partDir, "part-$id")
                    db.execSQL("UPDATE part SET _data = ? WHERE _id = ?", arrayOf(file.path, id))
                    Uri.parse("content://mms/part/$id")
                }

                segments.size >= 2 && segments[1] == "addr" -> {
                    v.put("msg_id", segments[0].toLong())
                    val id = db.insert("addr", null, v)
                    Uri.parse("content://mms/${segments[0]}/addr/$id")
                }

                else -> null
            }

            else -> null
        }
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val segments = uri.pathSegments
        return when {
            uri.authority == MMS && segments.firstOrNull() == "part" && segments.size == 2 ->
                db.delete("part", "_id = ?", arrayOf(segments[1]))

            uri.authority == MMS && segments.size == 1 -> {
                db.delete("part", "mid = ?", arrayOf(segments[0]))
                db.delete("addr", "msg_id = ?", arrayOf(segments[0]))
                db.delete("pdu", "_id = ?", arrayOf(segments[0]))
            }

            uri.authority == SMS && segments.size == 1 -> db.delete("sms", "_id = ?", arrayOf(segments[0]))
            else -> 0
        }
    }

    override fun getType(uri: Uri): String? = null

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val segments = uri.pathSegments
        if (uri.authority != MMS || segments.firstOrNull() != "part" || segments.size != 2) return null
        val partId = segments[1]
        if (mode.contains('w') && (failAllPartWrites || partId in failingPartWrites)) {
            throw java.io.FileNotFoundException("write refused for part $partId")
        }
        if (!mode.contains('w') && partId.toLong() in unreadableParts) {
            throw java.io.FileNotFoundException("part $partId is gone")
        }
        val path = db.rawQuery("SELECT _data FROM part WHERE _id = ?", arrayOf(partId)).use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: throw java.io.FileNotFoundException("no part $partId")
        val file = File(path)
        if (!file.exists()) file.createNewFile()
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /** The real part table calls its message column "mid"; the app asks for Telephony's MSG_ID, which is "mid". */
    private fun partSelection(selection: String?) = selection?.takeIf { it.isNotBlank() }
    private fun partProjection(projection: Array<out String>?) = projection?.map { it }?.toTypedArray()

    private fun withId(id: Long?, selection: String?, args: Array<out String>?): Pair<String?, Array<String>?> {
        if (id == null) return selection?.takeIf { it.isNotBlank() } to args?.toArgs()
        val sel = if (selection.isNullOrBlank()) "_id = ?" else "_id = ? AND ($selection)"
        return sel to (arrayOf(id.toString()) + (args?.toArgs() ?: emptyArray()))
    }

    private fun Array<out String>.toArgs(): Array<String> = map { it }.toTypedArray()

    private fun key(address: String): String {
        val digits = address.filter { it.isDigit() }
        return if (digits.length >= 7) digits.takeLast(10) else address.lowercase()
    }

    @Synchronized
    private fun canonicalId(address: String): Long {
        val wanted = key(address)
        db.rawQuery("SELECT _id, address FROM canonical_addresses", null).use {
            while (it.moveToNext()) if (key(it.getString(1)) == wanted) return it.getLong(0)
        }
        return db.insert("canonical_addresses", null, ContentValues().apply { put("address", address) })
    }

    @Synchronized
    private fun threadId(recipients: List<String>): Long {
        require(recipients.isNotEmpty()) { "no recipients" }
        val ids = recipients.map { canonicalId(it) }.distinct().sorted().joinToString(" ")
        db.rawQuery("SELECT _id FROM threads WHERE recipient_ids = ?", arrayOf(ids)).use {
            if (it.moveToFirst()) return it.getLong(0)
        }
        return db.insert("threads", null, ContentValues().apply { put("recipient_ids", ids) })
    }

    private fun Cursor.toMaps(): List<Map<String, Any?>> = buildList {
        while (moveToNext()) {
            add((0 until columnCount).associate { i ->
                getColumnName(i) to when (getType(i)) {
                    Cursor.FIELD_TYPE_INTEGER -> getLong(i)
                    Cursor.FIELD_TYPE_NULL -> null
                    else -> getString(i)
                }
            })
        }
    }

    companion object {
        private const val SMS = "sms"
        private const val MMS = "mms"
        private const val MMS_SMS = "mms-sms"

        fun install(): FakeMessageStore =
            Robolectric.buildContentProvider(FakeMessageStore::class.java)
                .create(ProviderInfo().apply { authority = "$SMS;$MMS;$MMS_SMS" })
                .get()
    }
}
