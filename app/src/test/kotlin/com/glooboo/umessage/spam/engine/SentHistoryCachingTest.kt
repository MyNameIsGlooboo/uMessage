package com.glooboo.umessage.spam.engine

import android.app.Application
import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowLooper

/**
 * SE-19: every received text changes the message store. That must not make the next spam check read every sent
 * message again; only the changed row is read.
 */
@RunWith(AndroidJUnit4::class)
class SentHistoryCachingTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val normalizer = NumberNormalizer { "GB" }

    /** An SMS store that counts full scans (queries by message type) and notifies each row it inserts. */
    class CountingSmsProvider : ContentProvider() {
        private lateinit var db: SQLiteDatabase

        override fun onCreate(): Boolean {
            db = SQLiteDatabase.create(null)
            db.execSQL("CREATE TABLE sms (_id INTEGER PRIMARY KEY AUTOINCREMENT, address TEXT, type INTEGER)")
            fullScans = 0
            return true
        }

        override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor {
            if (selection.orEmpty().startsWith("${Telephony.Sms.TYPE} IN")) fullScans++
            return db.query("sms", projection, selection, args, null, null, sort)
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri {
            val row = ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, db.insert("sms", null, values))
            context!!.contentResolver.notifyChange(row, null)
            context!!.contentResolver.notifyChange(Telephony.MmsSms.CONTENT_URI, null)
            return row
        }

        override fun delete(uri: Uri, selection: String?, args: Array<String>?): Int {
            val id = ContentUris.parseId(uri)
            return db.delete("sms", "_id = ?", arrayOf(id.toString())).also { context!!.contentResolver.notifyChange(uri, null) }
        }

        override fun getType(uri: Uri): String? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0

        companion object {
            @Volatile var fullScans = 0
        }
    }

    @Before
    fun install() {
        Robolectric.setupContentProvider(CountingSmsProvider::class.java, "sms")
    }

    private fun insert(address: String, type: Int): Uri {
        val uri = app.contentResolver.insert(Telephony.Sms.CONTENT_URI, ContentValues().apply {
            put("address", address); put("type", type)
        })!!
        ShadowLooper.idleMainLooper()
        return uri
    }

    @Test
    fun receivedTextsDoNotTriggerAnotherFullScan() {
        insert("+447350000000", Telephony.Sms.MESSAGE_TYPE_SENT)
        val checker = SystemContactChecker(app, normalizer)
        assertThat(checker.sentTo("+447121234567")).isEqualTo(ContactLookup.NOT_FOUND)

        repeat(5) { insert("+44712000000$it", Telephony.Sms.MESSAGE_TYPE_INBOX) }
        repeat(5) { assertThat(checker.sentTo("+44712000000$it")).isEqualTo(ContactLookup.NOT_FOUND) }

        assertThat(CountingSmsProvider.fullScans).isEqualTo(1)
    }

    @Test
    fun aNewSentMessageIsPickedUpWithoutAFullScan() {
        val checker = SystemContactChecker(app, normalizer)
        assertThat(checker.sentTo("+447121234567")).isEqualTo(ContactLookup.NOT_FOUND)

        insert("+447121234567", Telephony.Sms.MESSAGE_TYPE_SENT)

        assertThat(checker.sentTo("07121234567")).isEqualTo(ContactLookup.FOUND)
        assertThat(CountingSmsProvider.fullScans).isEqualTo(1)
    }

    @Test
    fun deletingASentMessageReadsEverythingAgain() {
        val sent = insert("+447121234567", Telephony.Sms.MESSAGE_TYPE_SENT)
        val checker = SystemContactChecker(app, normalizer)
        assertThat(checker.sentTo("+447121234567")).isEqualTo(ContactLookup.FOUND)

        app.contentResolver.delete(sent, null, null)
        ShadowLooper.idleMainLooper()

        assertThat(checker.sentTo("+447121234567")).isEqualTo(ContactLookup.NOT_FOUND)
        assertThat(CountingSmsProvider.fullScans).isEqualTo(2)
    }
}
