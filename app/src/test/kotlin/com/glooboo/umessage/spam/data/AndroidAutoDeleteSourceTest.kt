package com.glooboo.umessage.spam.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.helpers.StarredMessages
import com.glooboo.umessage.helpers.messageNotes
import com.glooboo.umessage.helpers.starredDB
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.StarredMessage
import com.glooboo.umessage.spam.FirstRun
import com.glooboo.umessage.spam.FirstRunPhase
import com.glooboo.umessage.spam.FirstRunState
import com.glooboo.umessage.spam.FirstRunStore
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.spam.engine.SpamEngine
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric

/**
 * L5-1: the real [AndroidAutoDeleteSource] with the real stars and notes databases and a made-up phone store: a
 * starred text (by id, MMS too, or restored by its shape) and a noted text are kept, a plain one is not; the store
 * check works for an MMS; delete takes the store row first and the cache row only after; nothing runs before the first
 * sort. Every number is made up (+15550001234).
 */
@RunWith(AndroidJUnit4::class)
class AndroidAutoDeleteSourceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val beforeSource = SpamAutoDelete.sourceFor
    private lateinit var sms: SmsRows
    private lateinit var mms: MmsRows

    @Before
    fun setUp() {
        resetSharedMessagesDatabase()
        sms = SmsRows.install()
        mms = MmsRows.install()
        writeState(FirstRunState())
    }

    @After
    fun tearDown() {
        SpamAutoDelete.sourceFor = beforeSource
        writeState(FirstRunState())
        resetSharedMessagesDatabase()
        val field = SpamDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }
        (field.get(null) as? SpamDatabase)?.let { runCatching { it.close() } }
        field.set(null, null)
        listOf("repository", "filter", "settingsStore", "forwardPlanner", "checker").forEach { name ->
            runCatching { SpamEngine::class.java.getDeclaredField(name).apply { isAccessible = true }.set(null, null) }
        }
    }

    private fun writeState(state: FirstRunState) {
        FirstRunStore(context.getSharedPreferences(FirstRunStore.PREFS, Context.MODE_PRIVATE)).write(state)
        FirstRun.reload(context)
    }

    private fun message(id: Long, body: String, date: Int, mmsRow: Boolean = false) = Message(
        id = id, body = body, type = Telephony.Sms.MESSAGE_TYPE_INBOX, status = 0, participants = ArrayList(),
        date = date, read = true, threadId = THREAD, isMMS = mmsRow, attachment = null, senderPhoneNumber = SENDER,
        senderName = "", senderPhotoUri = "", subscriptionId = 1,
    )

    private fun star(id: Long, isMms: Boolean, body: String, date: Int) = offMainThread {
        runBlocking {
            context.starredDB.insert(
                StarredMessage(id, isMms, THREAD, SENDER, date.toLong(), StarredMessages.fingerprint(body), 1, body)
            )
        }
    }

    private fun cache(vararg messages: Message) =
        offMainThread { messages.forEach { context.messagesDB.insertOrUpdate(it) } }

    private fun cachedIds(): List<Long> =
        offMainThread { context.messagesDB.getThreadMessages(THREAD).map { it.id }.sorted() }

    @Test
    fun starredAndNotedTextsAreKeptAndAPlainOneIsNot() {
        val starredSms = message(1, "Advert one", 1_700_000_001)
        val starredMms = message(2, "Advert two", 1_700_000_002, mmsRow = true)
        val restored = message(3, "Advert three", 1_700_000_003)
        val noted = message(4, "Advert four", 1_700_000_004)
        val plain = message(5, "Advert five", 1_700_000_005)
        // The MMS with the id of the starred SMS is another message.
        val sameIdMms = message(1, "Advert six", 1_700_000_006, mmsRow = true)
        star(1, false, starredSms.body, starredSms.date)
        star(2, true, starredMms.body, starredMms.date)
        // A restored star is not linked to its message yet: another id, but the same second, text and sender.
        star(900, false, restored.body, restored.date)
        offMainThread { runBlocking { context.messageNotes.setNote(noted, "rent") } }

        val source = AndroidAutoDeleteSource(context)
        val all = listOf(starredSms, starredMms, restored, noted, plain, sameIdMms)
        val kept = offMainThread { all.map(source::isKept) }

        assertThat(kept).containsExactly(true, true, true, true, false, false).inOrder()
    }

    @Test
    fun theStoreCheckReadsAnMmsRowAndRefusesAnotherOne() {
        val thread = SpamThread(threadId = THREAD, sender = SENDER, cause = SpamCause.UNKNOWN, spamSince = 1_000)
        val same = mms.add(SENDER, "Picture advert", date = 5_000)
        val sent = mms.add(SENDER, "Reply", date = 5_000, box = Telephony.Mms.MESSAGE_BOX_SENT)
        val source = AndroidAutoDeleteSource(context)

        fun matches(message: Message, of: SpamThread = thread) = offMainThread { source.storeMatches(message, of) }

        assertThat(matches(message(same, "Picture advert", 5_000, mmsRow = true))).isTrue()
        assertThat(matches(message(same, "Another text", 5_000, mmsRow = true))).isFalse()
        assertThat(matches(message(same, "Picture advert", 5_001, mmsRow = true))).isFalse()
        assertThat(matches(message(same, "Picture advert", 5_000, mmsRow = true).copy(threadId = 8))).isFalse()
        val otherSender = thread.copy(sender = OTHER)
        assertThat(matches(message(same, "Picture advert", 5_000, mmsRow = true), otherSender)).isFalse()
        assertThat(matches(message(sent, "Reply", 5_000, mmsRow = true))).isFalse()
        assertThat(matches(message(same + 99, "Picture advert", 5_000, mmsRow = true))).isFalse()
    }

    @Test
    fun deleteTakesTheStoreRowFirstAndTheCacheRowOnlyAfterIt() {
        val smsId = sms.add(SENDER, "Advert", 5_000_000)
        val mmsId = mms.add(SENDER, "Picture advert", date = 6_000)
        val gone = message(smsId + 100, "Gone from the store", 7_000)
        cache(message(smsId, "Advert", 5_000), message(mmsId, "Picture advert", 6_000, mmsRow = true), gone)
        val source = AndroidAutoDeleteSource(context)

        assertThat(offMainThread { source.delete(message(smsId, "Advert", 5_000)) }).isTrue()
        assertThat(offMainThread { source.delete(message(mmsId, "Picture advert", 6_000, mmsRow = true)) }).isTrue()
        // The store deletes no row: the cached row stays.
        assertThat(offMainThread { source.delete(gone) }).isFalse()

        assertThat(sms.has(smsId)).isFalse()
        assertThat(mms.has(mmsId)).isFalse()
        assertThat(cachedIds()).containsExactly(gone.id)
    }

    @Test
    fun theDeleterTakesTheRealStoreRowsOfPlainTextsOnly() {
        val thread = SpamThread(THREAD, SENDER, SpamCause.UNKNOWN, spamSince = 1_600_000_000_000L)
        val rows = listOf(
            Triple("Plain sms", 1_700_000_001, false),
            Triple("Starred sms", 1_700_000_002, false),
            Triple("Noted sms", 1_700_000_003, false),
            Triple("Restored sms", 1_700_000_004, false),
            Triple("Plain mms", 1_700_000_005, true),
            Triple("Starred mms", 1_700_000_006, true),
        )
        val messages = rows.map { (body, date, isMms) ->
            val id = if (isMms) mms.add(SENDER, body, date.toLong()) else sms.add(SENDER, body, date * 1000L)
            message(id, body, date, isMms)
        }
        val plainSms = messages[0]
        val starredSms = messages[1]
        val notedSms = messages[2]
        val restoredSms = messages[3]
        val plainMms = messages[4]
        val starredMms = messages[5]
        cache(*messages.toTypedArray())
        star(starredSms.id, false, starredSms.body, starredSms.date)
        star(starredMms.id, true, starredMms.body, starredMms.date)
        star(900, false, restoredSms.body, restoredSms.date)
        offMainThread { runBlocking { context.messageNotes.setNote(notedSms, "rent") } }
        val real = AndroidAutoDeleteSource(context)
        val source = object : AutoDeleteSource by real {
            override fun canDelete() = true
            override fun spamThreads() = listOf(thread)
            override fun afterThread(threadId: Long) = Unit
        }

        val deleted = offMainThread { SpamAutoDeleter(source) { 1_800_000_000_000L }.run(days = 30) }

        assertThat(deleted).isEqualTo(2)
        assertThat(sms.has(plainSms.id)).isFalse()
        assertThat(mms.has(plainMms.id)).isFalse()
        assertThat(listOf(starredSms, notedSms, restoredSms).map { sms.has(it.id) }).containsExactly(true, true, true)
        assertThat(mms.has(starredMms.id)).isTrue()
        assertThat(offMainThread { context.messagesDB.getThreadMessages(THREAD).map { it.body } })
            .containsExactly("Starred sms", "Noted sms", "Restored sms", "Starred mms")
    }

    @Test
    fun nothingRunsAndNoSourceIsBuiltBeforeTheFirstSort() {
        var built = 0
        SpamAutoDelete.sourceFor = {
            built++
            AndroidAutoDeleteSource(it)
        }
        writeState(FirstRunState(phase = FirstRunPhase.HISTORY, sortPublished = false))

        assertThat(offMainThread { SpamAutoDelete.run(context) }).isEqualTo(0)
        assertThat(built).isEqualTo(0)

        // After the first sort the run builds its source (auto-delete is off here, so it deletes nothing).
        writeState(FirstRunState(phase = FirstRunPhase.DONE, sortPublished = true))
        assertThat(offMainThread { SpamAutoDelete.run(context) }).isEqualTo(0)
        assertThat(built).isEqualTo(1)
    }

    /** The `sms` provider with delete: rows by id. */
    class SmsRows : ContentProvider() {
        private lateinit var db: SQLiteDatabase

        override fun onCreate(): Boolean {
            db = SQLiteDatabase.create(null)
            db.execSQL(
                "CREATE TABLE sms (_id INTEGER PRIMARY KEY AUTOINCREMENT, thread_id INTEGER, address TEXT, " +
                    "body TEXT, date INTEGER, type INTEGER)"
            )
            return true
        }

        fun add(address: String, body: String, date: Long, type: Int = Telephony.Sms.MESSAGE_TYPE_INBOX): Long =
            db.insert("sms", null, ContentValues().apply {
                put("thread_id", THREAD)
                put("address", address)
                put("body", body)
                put("date", date)
                put("type", type)
            })

        fun has(id: Long): Boolean =
            db.rawQuery("SELECT 1 FROM sms WHERE _id = ?", arrayOf("$id")).use { it.count > 0 }

        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val id = uri.pathSegments.firstOrNull()?.toLongOrNull()
            val sel = listOfNotNull(id?.let { "_id = $it" }, selection?.let { "($it)" }).joinToString(" AND ")
            return db.query(
                "sms", projection?.map { it }?.toTypedArray(), sel.ifEmpty { null },
                selectionArgs?.map { it }?.toTypedArray(), null, null, sortOrder,
            )
        }

        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
            val id = uri.pathSegments.firstOrNull()?.toLongOrNull() ?: return 0
            return db.delete("sms", "_id = ?", arrayOf("$id"))
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

        override fun getType(uri: Uri): String? = null

        companion object {
            fun install(): SmsRows =
                Robolectric.setupContentProvider(SmsRows::class.java, Telephony.Sms.CONTENT_URI.authority)
        }
    }

    /** The `mms` provider as the app reads it: rows (pdu) with their sender (addr) and text part (part). */
    class MmsRows : ContentProvider() {
        private lateinit var db: SQLiteDatabase

        override fun onCreate(): Boolean {
            db = SQLiteDatabase.create(null)
            db.execSQL(
                "CREATE TABLE pdu (_id INTEGER PRIMARY KEY AUTOINCREMENT, thread_id INTEGER, msg_box INTEGER, " +
                    "date INTEGER, read INTEGER, st INTEGER, sub_id INTEGER)"
            )
            db.execSQL(
                "CREATE TABLE addr (_id INTEGER PRIMARY KEY AUTOINCREMENT, msg_id INTEGER, address TEXT, type INTEGER)"
            )
            db.execSQL("CREATE TABLE part (_id INTEGER PRIMARY KEY AUTOINCREMENT, mid INTEGER, ct TEXT, text TEXT)")
            return true
        }

        /** An MMS (date in seconds) from [sender] with one text part. Its id. */
        fun add(sender: String, text: String, date: Long, box: Int = Telephony.Mms.MESSAGE_BOX_INBOX): Long {
            val id = db.insert("pdu", null, ContentValues().apply {
                put("thread_id", THREAD)
                put("msg_box", box)
                put("date", date)
                put("read", 1)
                put("st", 0)
                put("sub_id", 1)
            })
            db.insert("addr", null, ContentValues().apply {
                put("msg_id", id)
                put("address", sender)
                put("type", FROM)
            })
            db.insert("part", null, ContentValues().apply {
                put("mid", id)
                put("ct", "text/plain")
                put("text", text)
            })
            return id
        }

        fun has(id: Long): Boolean =
            db.rawQuery("SELECT 1 FROM pdu WHERE _id = ?", arrayOf("$id")).use { it.count > 0 }

        private fun target(uri: Uri): Pair<String, String?> {
            val segments = uri.pathSegments
            val id = segments.firstOrNull()?.toLongOrNull()
            return when {
                segments.firstOrNull() == "part" -> "part" to null
                id != null && segments.getOrNull(1) == "addr" -> "addr" to "msg_id = $id"
                id != null -> "pdu" to "_id = $id"
                else -> "pdu" to null
            }
        }

        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val (table, filter) = target(uri)
            val sel = listOfNotNull(filter, selection?.let { "($it)" }).joinToString(" AND ").ifEmpty { null }
            return db.query(
                table, projection?.map { it }?.toTypedArray(), sel,
                selectionArgs?.map { it }?.toTypedArray(), null, null, sortOrder,
            )
        }

        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
            val id = uri.pathSegments.firstOrNull()?.toLongOrNull() ?: return 0
            db.delete("addr", "msg_id = ?", arrayOf("$id"))
            db.delete("part", "mid = ?", arrayOf("$id"))
            return db.delete("pdu", "_id = ?", arrayOf("$id"))
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

        override fun getType(uri: Uri): String? = null

        companion object {
            fun install(): MmsRows =
                Robolectric.setupContentProvider(MmsRows::class.java, Telephony.Mms.CONTENT_URI.authority)
        }
    }

    private companion object {
        const val THREAD = 7L
        const val SENDER = "+15550001234"
        const val OTHER = "+15550001235"
        const val FROM = 137
    }
}
