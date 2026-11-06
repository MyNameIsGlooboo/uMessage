package com.glooboo.umessage.extensions

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.testing.FakeSmsMmsProvider
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import java.util.concurrent.atomic.AtomicInteger

/**
 * DL-19: opening a chat with many picture messages asked the phone several small questions per message. The parts of
 * all of them now come in one query, and a one-to-one chat looks up its participants once.
 */
@RunWith(AndroidJUnit4::class)
class GetMessagesMmsQueriesTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var mms: CountingMmsProvider

    @Before
    fun setUp() {
        FakeSmsMmsProvider.installSms()
        mms = Robolectric.buildContentProvider(CountingMmsProvider::class.java)
            .create(ProviderInfo().apply { authority = "mms;mms-sms" })
            .get()
        repeat(MESSAGES) { i ->
            mms.addMms(id = 100L + i, box = if (i % 2 == 0) Telephony.Mms.MESSAGE_BOX_INBOX else Telephony.Mms.MESSAGE_BOX_SENT)
        }
    }

    @After
    fun tearDown() = resetSharedMessagesDatabase()

    @Test
    fun aChatWithFiftyPicturesTakesAHandfulOfQueries() {
        val messages = app.getMessages(THREAD, includeScheduledMessages = false)

        assertThat(messages).hasSize(MESSAGES)
        // mms rows, their parts, the thread's recipients and its one address: not a few per message.
        assertThat(mms.queries.get()).isAtMost(5)
    }

    @Test
    fun everyMessageStillGetsItsOwnTextPhotoAndSender() {
        val messages = app.getMessages(THREAD, includeScheduledMessages = false).associateBy { it.id }

        val received = messages.getValue(104L)
        assertThat(received.body).isEqualTo("photo 104")
        assertThat(received.attachment!!.attachments.map { it.uriString }).containsExactly("content://mms/part/${104 * 10 + 1}")
        assertThat(received.senderPhoneNumber).isEqualTo("111")
        assertThat(received.participants.single().phoneNumbers.single().normalizedNumber).isEqualTo("111")

        val sent = messages.getValue(105L)
        assertThat(sent.body).isEqualTo("photo 105")
        assertThat(sent.senderPhoneNumber).isEmpty()
    }

    /** `content://mms`, `content://mms/part` and the two `content://mms-sms` lookups a thread needs, counted. */
    class CountingMmsProvider : ContentProvider() {
        val queries = AtomicInteger()
        private lateinit var db: SQLiteDatabase

        override fun onCreate(): Boolean {
            db = SQLiteDatabase.create(null)
            db.execSQL(
                "CREATE TABLE pdu (_id INTEGER PRIMARY KEY, thread_id INTEGER, msg_box INTEGER, date INTEGER, " +
                    "read INTEGER, sub_id INTEGER, st INTEGER)"
            )
            db.execSQL("CREATE TABLE part (_id INTEGER PRIMARY KEY, mid INTEGER, ct TEXT, text TEXT)")
            return true
        }

        fun addMms(id: Long, box: Int) {
            db.insert("pdu", null, ContentValues().apply {
                put("_id", id); put("thread_id", THREAD); put("msg_box", box); put("date", 1_700_000_000L + id)
                put("read", 1); put("sub_id", -1); put("st", 0)
            })
            db.insert("part", null, ContentValues().apply { put("_id", id * 10); put("mid", id); put("ct", "text/plain"); put("text", "photo $id") })
            db.insert("part", null, ContentValues().apply { put("_id", id * 10 + 1); put("mid", id); put("ct", "image/jpeg") })
        }

        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor? {
            queries.incrementAndGet()
            val segments = uri.pathSegments
            return when {
                uri.authority == "mms-sms" && segments.firstOrNull() == "conversations" ->
                    MatrixCursor(arrayOf(Telephony.ThreadsColumns.RECIPIENT_IDS)).apply { addRow(arrayOf("1")) }

                uri.authority == "mms-sms" && segments.firstOrNull() == "canonical-addresses" ->
                    MatrixCursor(arrayOf(Telephony.Mms.Addr.ADDRESS)).apply { addRow(arrayOf("111")) }

                segments.firstOrNull() == "part" ->
                    db.query("part", projection?.map { it }?.toTypedArray(), selection, selectionArgs?.map { it }?.toTypedArray(), null, null, sortOrder)

                segments.getOrNull(1) == "addr" ->
                    MatrixCursor(arrayOf(Telephony.Mms.Addr.ADDRESS)).apply { addRow(arrayOf("111")) }

                segments.isEmpty() -> {
                    val order = sortOrder?.substringBefore(" LIMIT ")
                    val limit = sortOrder?.substringAfter(" LIMIT ", "")?.ifEmpty { null }
                    db.query("pdu", projection?.map { it }?.toTypedArray(), selection, selectionArgs?.map { it }?.toTypedArray(), null, null, order, limit)
                }

                else -> null
            }
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun getType(uri: Uri): String? = null
    }

    private companion object {
        const val THREAD = 9L
        const val MESSAGES = 50
    }
}
