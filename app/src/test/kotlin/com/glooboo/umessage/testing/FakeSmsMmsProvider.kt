package com.glooboo.umessage.testing

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.Telephony
import org.robolectric.Robolectric

/**
 * A minimal stand-in for the telephony provider, which Robolectric does not have. It keeps `content://sms` rows and
 * `content://mms` rows in an in-memory SQLite table each, and understands `content://<authority>` and
 * `content://<authority>/<id>`, with selections. Install with [installSms] / [installMms].
 */
open class FakeSmsMmsProvider : ContentProvider() {

    private lateinit var db: SQLiteDatabase

    override fun onCreate(): Boolean {
        db = SQLiteDatabase.create(null)
        db.execSQL(
            "CREATE TABLE sms (_id INTEGER PRIMARY KEY AUTOINCREMENT, thread_id INTEGER, address TEXT, body TEXT, " +
                "date INTEGER, date_sent INTEGER, type INTEGER, status INTEGER DEFAULT -1, read INTEGER, seen INTEGER, " +
                "sub_id INTEGER)"
        )
        db.execSQL(
            "CREATE TABLE mms (_id INTEGER PRIMARY KEY AUTOINCREMENT, thread_id INTEGER, msg_box INTEGER, " +
                "date INTEGER, read INTEGER, seen INTEGER)"
        )
        return true
    }

    private fun table(uri: Uri) = if (uri.authority == Telephony.Mms.CONTENT_URI.authority) "mms" else "sms"

    /** The row id in `content://sms/<id>`, or null for the whole table. */
    private fun idOf(uri: Uri): Long? = uri.pathSegments.firstOrNull()?.toLongOrNull()

    private fun where(uri: Uri, selection: String?, args: Array<out String>?): Pair<String?, Array<String>?> {
        val id = idOf(uri) ?: return selection to args?.map { it }?.toTypedArray()
        val sel = if (selection.isNullOrEmpty()) "_id = ?" else "_id = ? AND ($selection)"
        return sel to (arrayOf(id.toString()) + (args?.map { it } ?: emptyList()))
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val (sel, args) = where(uri, selection, selectionArgs)
        return db.query(table(uri), projection?.map { it }?.toTypedArray(), sel, args, null, null, sortOrder)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        val id = db.insertOrThrow(table(uri), null, values)
        return ContentUris.withAppendedId(Uri.parse("content://${uri.authority}"), id)
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        val (sel, args) = where(uri, selection, selectionArgs)
        return db.update(table(uri), values, sel, args)
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        val (sel, args) = where(uri, selection, selectionArgs)
        return db.delete(table(uri), sel, args)
    }

    override fun getType(uri: Uri): String? = null

    /** Reads one column of one row, for assertions. */
    fun intColumn(table: String, id: Long, column: String): Int? =
        db.rawQuery("SELECT $column FROM $table WHERE _id = ?", arrayOf(id.toString())).use {
            if (it.moveToFirst()) it.getInt(0) else null
        }

    /** Every sms row as address to type, in insertion order. */
    fun smsTypes(): List<Pair<String, Int>> =
        db.rawQuery("SELECT address, type FROM sms ORDER BY _id", null).use {
            buildList { while (it.moveToNext()) add(it.getString(0) to it.getInt(1)) }
        }

    /** Every sms row as id to address, in insertion order. */
    fun smsIds(): List<Pair<Long, String>> =
        db.rawQuery("SELECT _id, address FROM sms ORDER BY _id", null).use {
            buildList { while (it.moveToNext()) add(it.getLong(0) to it.getString(1)) }
        }

    companion object {
        fun installSms(): FakeSmsMmsProvider =
            Robolectric.setupContentProvider(FakeSmsMmsProvider::class.java, Telephony.Sms.CONTENT_URI.authority)

        fun installMms(): FakeSmsMmsProvider =
            Robolectric.setupContentProvider(FakeSmsMmsProvider::class.java, Telephony.Mms.CONTENT_URI.authority)
    }
}
