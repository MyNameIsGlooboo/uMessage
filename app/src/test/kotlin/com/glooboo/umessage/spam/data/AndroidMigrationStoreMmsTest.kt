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
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.testing.ChatNotices
import com.glooboo.umessage.testing.MmsPduFixtures
import com.google.android.mms.pdu_alt.CharacterSets
import com.google.android.mms.pdu_alt.EncodedStringValue
import com.google.android.mms.pdu_alt.PduBody
import com.google.android.mms.pdu_alt.PduComposer
import com.google.android.mms.pdu_alt.PduPart
import com.google.android.mms.pdu_alt.SendReq
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric

/**
 * F-8: the real MMS side of the migration's store code (AndroidMigrationStore) against a made-up `mms` provider
 * (rows, addresses and parts as mmslib writes them), the identity of a kept PDU read the way mmslib stores its text
 * (F-7: UTF-8, also for ISO-8859-1 and UTF-16 parts), and auto-delete's check of a cached row against the store
 * (M-D2). Every number is made up (from +15550001234).
 */
@RunWith(AndroidJUnit4::class)
class AndroidMigrationStoreMmsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun store() = AndroidMigrationStore(context)

    private fun attempt(newest: Long, startedAtSeconds: Long) = MmsAttempt(1, newest, startedAtSeconds * 1000, 0L)

    @Test
    fun theMessageIdProbeTakesOnlyADownloadedMmsInTheInbox() {
        val mms = MmsStore.install()
        mms.add(SENDER, date = 100, messageId = "mid-1", type = NOTICE)
        mms.add(SENDER, date = 100, messageId = "mid-1", box = Telephony.Mms.MESSAGE_BOX_SENT)
        val real = mms.add(SENDER, date = 100, messageId = "mid-1")

        assertThat(store().findMms(MmsIdentity("mid-1", 0), SENDER, 100)).isEqualTo(real)
        // Another carrier message of the same sender and second is not this one.
        assertThat(store().findMms(MmsIdentity("mid-2", 0), SENDER, 100)).isNull()
    }

    @Test
    fun aRestoredMmsWithoutItsMessageIdIsFoundBySenderAndTimeAndPassesTheVerify() {
        val mms = MmsStore.install()
        // S2-1: a backup does not carry m_id, so the restored copy has none.
        val restored = mms.add(SENDER, date = 300)
        mms.part(restored, "text/plain", "Look")
        val identity = MmsIdentity("mid-held", "Look".length, pduDate = 295)

        assertThat(store().findMms(identity, SENDER, 300)).isEqualTo(restored)
        assertThat(store().findMms(identity, OTHER, 300)).isNull()
        assertThat(store().checkMms(restored, SENDER, 300, identity)).isTrue()
        // The same date sent by the carrier counts too (a copy dated otherwise).
        val sent = mms.add(SENDER, date = 999, dateSent = 295)
        assertThat(store().findMms(identity.copy(messageId = null), SENDER, 301)).isEqualTo(sent)
    }

    @Test
    fun withoutAMessageIdTheProbeNeedsTheSenderAndTheTime() {
        val mms = MmsStore.install()
        mms.add(OTHER, date = 200)
        mms.add(SENDER, date = 201)
        val real = mms.add(SENDER, date = 200)

        assertThat(store().findMms(MmsIdentity(null, 0), SENDER, 200)).isEqualTo(real)
        assertThat(store().findMms(MmsIdentity(null, 0), SENDER, 202)).isNull()
    }

    @Test
    fun theNewestMmsIdNeedsNoLimitInTheSortOrder() {
        val mms = MmsStore.install()
        assertThat(store().newestMmsId()).isEqualTo(0L)
        mms.add(SENDER, date = 1)
        val newest = mms.add(SENDER, date = 2)

        assertThat(store().newestMmsId()).isEqualTo(newest)
        assertThat(mms.sortOrders.none { it.contains("LIMIT", ignoreCase = true) }).isTrue()
    }

    @Test
    fun aWholeCutOffWriteIsFoundByThePdusOwnDateAndAHalfOneByItsMissingAddresses() {
        val mms = MmsStore.install()
        val before = mms.add(SENDER, date = 50)
        // A group PDU: FROM and two TO rows.
        val identity = MmsIdentity(null, 0, pduDate = 1_000, addressCount = 3)
        // A whole write of the attempt: mmslib's time of the write, the PDU's date as date_sent.
        val whole = mms.add(SENDER, date = 5_010, dateSent = 1_000, moreAddresses = 2)
        assertThat(store().findMmsAfter(attempt(before, 5_000), SENDER, identity)).isEqualTo(whole)
        mms.delete(whole)
        // S2-5: a later MMS of the same sender near the write's time but with its own date is not it.
        val later = mms.add(SENDER, date = 5_020, dateSent = 5_000, moreAddresses = 2)
        assertThat(store().findMmsAfter(attempt(before, 5_000), SENDER, identity)).isNull()
        mms.delete(later)

        // A real download of the same sender, still being written (no address yet, its own date): not uMessage's.
        mms.add(null, date = 5_020)
        assertThat(store().findHalfWrittenMms(attempt(before, 5_000), identity)).isNull()
        // uMessage's half write cut between its addresses (S2-4): the PDU's own date, two of three addresses.
        val half = mms.add(SENDER, date = 1_000, moreAddresses = 1)
        assertThat(store().findHalfWrittenMms(attempt(before, 5_000), identity)).isEqualTo(half)
        assertThat(store().findMmsAfter(attempt(before, 5_000), SENDER, identity)).isNull()

        assertThat(store().deleteMms(half)).isTrue()
        assertThat(mms.addresses(half)).isEmpty()
    }

    @Test
    fun theVerifyReadsTheTextAsMmslibStoredItAndTheSettleDatesAndReadsTheRow() {
        val mms = MmsStore.install()
        val pdu = MmsPduFixtures.retrieveConf(context, "Hello there")
        val identity = MmsIdentities.of(pdu)!!
        val id = mms.add(SENDER, date = 9_999, read = 0)
        mms.part(id, "text/plain", "Hello there")
        mms.part(id, "application/smil", "<smil/>")

        assertThat(store().checkMms(id, SENDER, 1_700, identity)).isFalse()
        assertThat(store().settleMms(id, 1_700_000L)).isEqualTo(Settle.DONE)
        assertThat(store().settleMms(id + 99, 1_700_000L)).isEqualTo(Settle.GONE)
        assertThat(mms.row(id)).containsAtLeast("date", "1700", "read", "1", "seen", "1")
        assertThat(store().checkMms(id, SENDER, 1_700, identity)).isTrue()
        assertThat(store().checkMms(id, OTHER, 1_700, identity)).isFalse()
        assertThat(store().checkMms(id, SENDER, 1_700, identity.copy(textLength = 3))).isFalse()
        val other = mms.add(SENDER, date = 1_700, messageId = "mid-other")
        mms.part(other, "text/plain", "Hello there")
        assertThat(store().checkMms(other, SENDER, 1_700, identity.copy(messageId = "mid-mine"))).isFalse()
    }

    @Test
    fun aTextPartInAnotherCharsetVerifiesAsMmslibStoresIt() {
        for ((charset, bytes) in listOf(
            CharacterSets.UTF_8 to "Grüße".toByteArray(Charsets.UTF_8),
            CharacterSets.ISO_8859_1 to "Grüße".toByteArray(Charsets.ISO_8859_1),
            CharacterSets.UCS2 to "Hi".toByteArray(Charsets.UTF_16),
        )) {
            val mms = MmsStore.install()
            val identity = MmsIdentities.of(pdu(bytes, charset))!!
            // mmslib's persist: the text column holds the bytes decoded as UTF-8, whatever the part's charset.
            val stored = EncodedStringValue(bytes).string
            assertThat(identity.textLength).isEqualTo(stored.trim().length)
            val id = mms.add(SENDER, date = 10)
            mms.part(id, "text/plain", stored)

            assertThat(store().checkMms(id, SENDER, 10, identity)).isTrue()
        }
    }

    @Test
    fun autoDeleteTakesOnlyTheStoreRowThatIsStillTheCachedMessage() {
        val sms = SpamProviderMigrationTest.SmsStore.install()
        val thread = SpamThread(threadId = 7, sender = SENDER, cause = SpamCause.UNKNOWN, spamSince = 1_000)
        val same = sms.insertRaw(SENDER, "Advert", 5_000_000, Telephony.Sms.MESSAGE_TYPE_INBOX, threadId = 7)
        val sent = sms.insertRaw(SENDER, "Reply", 5_000_000, Telephony.Sms.MESSAGE_TYPE_SENT, threadId = 7)
        val source = AndroidAutoDeleteSource(context)

        assertThat(source.storeMatches(cached(same, "Advert"), thread)).isTrue()
        // A rebuilt store gave the id to another text, another chat or another sender: nothing is deleted.
        assertThat(source.storeMatches(cached(same, "Another text"), thread)).isFalse()
        assertThat(source.storeMatches(cached(same, "Advert", threadId = 8), thread)).isFalse()
        assertThat(source.storeMatches(cached(same, "Advert"), thread.copy(sender = OTHER))).isFalse()
        assertThat(source.storeMatches(cached(same, "Advert", date = 5_001), thread)).isFalse()
        assertThat(source.storeMatches(cached(sent, "Reply"), thread)).isFalse()
        assertThat(source.storeMatches(cached(same + 99, "Advert"), thread)).isFalse()
    }

    @Test
    fun aSpamChatAutoDeleteEmptiedLosesItsNoticeInTheShade() {
        SpamProviderMigrationTest.SmsStore.install()
        MmsStore.install()
        ChatNotices.post(context, 7)

        // The real run is off the main thread (the database refuses the main one).
        val worker = Thread { AndroidAutoDeleteSource(context).afterThread(7) }
        worker.start()
        worker.join()

        assertThat(ChatNotices.isShown(context, 7)).isFalse()
    }

    private fun cached(id: Long, body: String, threadId: Long = 7, date: Int = 5_000) = Message(
        id = id, body = body, type = Telephony.Sms.MESSAGE_TYPE_INBOX, status = 0, participants = ArrayList(),
        date = date, read = true, threadId = threadId, isMMS = false, attachment = null, senderPhoneNumber = SENDER,
        senderName = "", senderPhotoUri = "", subscriptionId = 1,
    )

    /** An m-retrieve-conf with one text part of [bytes] in [charset] (composed like MmsPduFixtures). */
    private fun pdu(bytes: ByteArray, charset: Int): ByteArray {
        val req = SendReq()
        req.from = EncodedStringValue(SENDER)
        req.addTo(EncodedStringValue(OTHER))
        req.date = 1_700_000_000L
        req.contentType = "application/vnd.wap.multipart.related".toByteArray()
        val part = PduPart().apply {
            setContentType("text/plain".toByteArray())
            setContentLocation("text.txt".toByteArray())
            setContentId("<text.txt>".toByteArray())
            setCharset(charset)
            setData(bytes)
        }
        req.body = PduBody().apply { addPart(part) }
        val composed = PduComposer(context, req).make() ?: error("PduComposer failed")
        composed[1] = 0x84.toByte() // X-Mms-Message-Type: m-retrieve-conf
        return composed
    }

    /** The `mms` provider as mmslib leaves it: rows (pdu), their addresses (addr) and parts (part). */
    class MmsStore : ContentProvider() {
        private lateinit var db: SQLiteDatabase
        val sortOrders = mutableListOf<String>()

        override fun onCreate(): Boolean {
            db = SQLiteDatabase.create(null)
            db.execSQL(
                "CREATE TABLE pdu (_id INTEGER PRIMARY KEY AUTOINCREMENT, thread_id INTEGER, msg_box INTEGER, " +
                    "m_type INTEGER, m_id TEXT, date INTEGER, date_sent INTEGER, read INTEGER, seen INTEGER, " +
                    "sub_id INTEGER)"
            )
            db.execSQL(
                "CREATE TABLE addr (_id INTEGER PRIMARY KEY AUTOINCREMENT, msg_id INTEGER, address TEXT, type INTEGER)"
            )
            db.execSQL(
                "CREATE TABLE part (_id INTEGER PRIMARY KEY AUTOINCREMENT, mid INTEGER, ct TEXT, text TEXT, " +
                    "seq INTEGER)"
            )
            return true
        }

        /** An MMS from [sender] (null: no address yet, a write cut off) with [moreAddresses] TO rows. Its id. */
        @Suppress("LongParameterList")
        fun add(
            sender: String?,
            date: Long,
            messageId: String? = null,
            box: Int = Telephony.Mms.MESSAGE_BOX_INBOX,
            type: Int = RETRIEVE_CONF,
            read: Int = 1,
            dateSent: Long = 0,
            moreAddresses: Int = 0,
        ): Long {
            val id = db.insert("pdu", null, ContentValues().apply {
                put("msg_box", box)
                put("m_type", type)
                put("m_id", messageId)
                put("date", date)
                put("date_sent", dateSent)
                put("read", read)
                put("seen", read)
            })
            sender?.let { address(id, it, FROM) }
            repeat(moreAddresses) { address(id, "+1555000124$it", TO) }
            return id
        }

        private fun address(id: Long, address: String, type: Int) {
            db.insert("addr", null, ContentValues().apply {
                put("msg_id", id)
                put("address", address)
                put("type", type)
            })
        }

        fun part(id: Long, type: String, text: String) {
            db.insert("part", null, ContentValues().apply {
                put("mid", id)
                put("ct", type)
                put("text", text)
            })
        }

        fun row(id: Long): Map<String, String?> = db.rawQuery("SELECT * FROM pdu WHERE _id = ?", arrayOf("$id")).use {
            it.moveToFirst()
            (0 until it.columnCount).associate { i -> it.getColumnName(i) to it.getString(i) }
        }

        fun addresses(id: Long): List<String> =
            db.rawQuery("SELECT address FROM addr WHERE msg_id = ?", arrayOf("$id")).use {
                buildList { while (it.moveToNext()) add(it.getString(0)) }
            }

        fun delete(id: Long) {
            db.delete("pdu", "_id = ?", arrayOf("$id"))
            db.delete("addr", "msg_id = ?", arrayOf("$id"))
        }

        /** The table and the row filter of [uri]: `mms`, `mms/<id>`, `mms/<id>/addr`, `mms/part`. */
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

        private fun where(filter: String?, selection: String?) =
            listOfNotNull(filter, selection?.let { "($it)" }).joinToString(" AND ").ifEmpty { null }

        override fun query(
            uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            sortOrder?.let { sortOrders += it }
            val (table, filter) = target(uri)
            return db.query(
                table, projection?.map { it }?.toTypedArray(), where(filter, selection),
                selectionArgs?.map { it }?.toTypedArray(), null, null, sortOrder,
            )
        }

        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
            val (table, filter) = target(uri)
            return db.update(table, values, where(filter, selection), args?.map { it }?.toTypedArray())
        }

        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int {
            val (table, filter) = target(uri)
            val gone = db.delete(table, where(filter, selection), args?.map { it }?.toTypedArray())
            if (table == "pdu" && filter != null) db.delete("addr", "msg_id = ${filter.substringAfter("= ")}", null)
            return gone
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null

        override fun getType(uri: Uri): String? = null

        companion object {
            fun install(): MmsStore =
                Robolectric.setupContentProvider(MmsStore::class.java, Telephony.Mms.CONTENT_URI.authority)
        }
    }

    private companion object {
        const val SENDER = "+15550001234"
        const val OTHER = "+15550001235"
        const val RETRIEVE_CONF = 132
        const val NOTICE = 130
        const val FROM = 137
        const val TO = 151
    }
}
