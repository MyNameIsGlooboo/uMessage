package com.glooboo.umessage.spam.data

import android.content.Context
import android.net.Uri
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import android.provider.Telephony.Threads
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.glooboo.umessage.BuildConfig
import com.glooboo.umessage.spam.FirstRun
import com.glooboo.umessage.spam.MMS_PLACEHOLDER_BODY
import com.glooboo.umessage.spam.SpamSorting
import com.glooboo.umessage.spam.mms.BlockedMmsStore
import com.glooboo.umessage.testing.DeviceTest
import com.glooboo.umessage.testing.NoForwarding
import com.glooboo.umessage.testing.SmsRole
import com.google.android.mms.pdu_alt.PduHeaders
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Spec 12.2, the message migration against the emulator's real telephony provider. A spamless.db of schema 8 (0.4.14)
 * holds SMS rows (on SIM 1, SIM 2 and SIM -1, one unread, the same text twice ten seconds apart), a text-only MMS, a
 * placeholder MMS and two kept MMS (one with a message id). It is opened as the app opens it (Room 8 to 9), then
 * [SpamProviderMigration] runs with the app's own Android mover on that file. Every held row with content is in the
 * store exactly once, read and seen, in its sender's thread, at its own date; the thread is dated by it; the
 * placeholder writes nothing. A second run changes nothing.
 *
 * The file has its own name and the journal its own preferences, so the app's live spamless.db and journal are not
 * touched. The test waits until the app's own start-up work and any first run are over, and its mover serves only
 * the test's thread, so an app-start run can never move the test file's rows (or the live file's rows into the
 * test's counts). The pass requests of the migration are stubbed for the test. Needs the SMS role (taken for the
 * test and kept until the uninstall). Forwarding is paused. Emulator only. Every number is made up and unknown to
 * the app.
 */
@RunWith(AndroidJUnit4::class)
class SpamProviderMigrationDeviceTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), SpamDatabase::class.java)

    private val app: Context = DeviceTest.context
    private var db: SpamDatabase? = null
    private val keptFiles = mutableListOf<String>()
    private val noForwarding = NoForwarding()
    private val testThread = Thread.currentThread()
    private val originalMover = SpamProviderMigration.moverFor
    private val originalRequestPass = SpamProviderMigration.requestPass
    private val originalAfterPass = SpamProviderMigration.afterPass

    /** A held row of the seed and what the migration must do with it. */
    private data class Held(
        val id: Long,
        val address: String,
        val body: String,
        val sim: Int,
        val at: Long,
        val isMms: Boolean = false,
        val media: String = "NONE",
        val file: String? = null,
        val unread: Boolean = false,
        val messageId: String? = null,
    ) {
        val goesAsMms get() = file != null
        val goesAsText get() = !goesAsMms && body != MMS_PLACEHOLDER_BODY
    }

    /** One received row of the store. [date] in milliseconds for SMS, seconds for MMS (as the store keeps them). */
    private data class Stored(
        val id: Long, val threadId: Long, val date: Long, val read: Int, val seen: Int, val messageId: String?,
    )

    private val base = (System.currentTimeMillis() - THREE_DAYS_MS) / 1000 * 1000
    private lateinit var seed: List<Held>

    @Before
    fun setUp() {
        DeviceTest.assumeEmulator()
        SmsRole.take()
        assumeTrue("uMessage must be the default SMS app to write to the store", SmsRole.isHeld())
        DeviceTest.assumeUnknownSenders(SENDERS)
        noForwarding.start()
        waitForAppStart()
        SpamProviderMigration.requestPass = { context, trigger ->
            if (Thread.currentThread() !== testThread) originalRequestPass(context, trigger)
        }
        SpamProviderMigration.afterPass = { context ->
            if (Thread.currentThread() !== testThread) originalAfterPass(context)
        }
        DeviceTest.forgetChats(SENDERS)
        app.deleteDatabase(TEST_DB)
        app.deleteSharedPreferences(TEST_JOURNAL)
    }

    /**
     * The app's start-up steps (App.onCreate, one background thread ending with the app's own migration run) are
     * over, no first run works, and the sorting thread has nothing queued.
     */
    private fun waitForAppStart() {
        val appStartRunning = {
            Thread.getAllStackTraces().values.any { frames ->
                frames.any { it.className == APP_CLASS || it.className.startsWith("$APP_CLASS$") }
            }
        }
        assertTrue("app start finished", DeviceTest.waitFor(timeoutMs = 120_000L) { !appStartRunning() })
        assertTrue("no first run", DeviceTest.waitFor(timeoutMs = 120_000L) { !FirstRun.state(app).value.active })
        val drained = CountDownLatch(1)
        SpamSorting.execute(Runnable { drained.countDown() })
        assertTrue("sorting idle", drained.await(120L, TimeUnit.SECONDS))
        // A run still inside the migration holds its lock; this waits for it.
        synchronized(SpamProviderMigration.lock) {}
    }

    @After
    fun tearDown() {
        SpamProviderMigration.moverFor = originalMover
        SpamProviderMigration.requestPass = originalRequestPass
        SpamProviderMigration.afterPass = originalAfterPass
        noForwarding.end()
        runCatching { db?.close() }
        app.deleteDatabase(TEST_DB)
        app.deleteSharedPreferences(TEST_JOURNAL)
        runCatching { BlockedMmsStore.delete(app, keptFiles) }
        DeviceTest.forgetChats(SENDERS)
    }

    private fun seedVersion8() {
        val kept1 = keepPdu(SENDERS[7], base + 7 * MINUTE_MS, "J10 kept picture 1", "mid-j10-1")
        val kept2 = keepPdu(SENDERS[8], base + 8 * MINUTE_MS, "J10 kept picture 2", null)
        seed = listOf(
            Held(1, SENDERS[0], "J10 held text 1", sim = 1, at = base, unread = true),
            Held(2, SENDERS[1], "J10 held text 2", sim = 2, at = base + MINUTE_MS),
            Held(3, SENDERS[2], "J10 held text 3", sim = -1, at = base + 2 * MINUTE_MS),
            Held(4, SENDERS[3], "J10 twice the same", sim = 1, at = base + 3 * MINUTE_MS),
            Held(5, SENDERS[3], "J10 twice the same", sim = 1, at = base + 3 * MINUTE_MS + 10_000L),
            Held(6, SENDERS[5], "J10 text of a lost picture", 1, base + 5 * MINUTE_MS, isMms = true, media = "MISSING"),
            Held(7, SENDERS[6], MMS_PLACEHOLDER_BODY, 1, base + 6 * MINUTE_MS, isMms = true, media = "MISSING"),
            Held(
                8, SENDERS[7], "J10 kept picture 1", 1, base + 7 * MINUTE_MS, isMms = true, media = "STORED",
                file = kept1, unread = true, messageId = "mid-j10-1",
            ),
            Held(
                9, SENDERS[8], "J10 kept picture 2", 1, base + 8 * MINUTE_MS, isMms = true, media = "STORED",
                file = kept2,
            ),
        )
        helper.createDatabase(TEST_DB, 8).use { v8 ->
            seed.forEach { row ->
                v8.execSQL(
                    "INSERT INTO blocked_messages (id, address, body, subscriptionId, isMms, receivedAt, reason, " +
                        "ruleId, media, mediaFile, unread) VALUES (?, ?, ?, ?, ?, ?, 'UNKNOWN_SENDER', NULL, ?, ?, ?)",
                    arrayOf<Any?>(
                        row.id, row.address, row.body, row.sim, if (row.isMms) 1 else 0, row.at, row.media, row.file,
                        if (row.unread) 1 else 0,
                    ),
                )
            }
        }
    }

    /** The kept file of a downloaded MMS, as 0.4.14 kept it (BlockedMmsStore); its name. */
    private fun keepPdu(sender: String, at: Long, text: String, messageId: String?): String {
        val pdu = RetrieveConfPdu.make(sender, listOf(EMULATOR_SELF), text, at / 1000 - 5, messageId)
        val name = BlockedMmsStore.save(app, pdu)
        assertNotNull("the made-up MMS is a valid m-retrieve-conf", name)
        keptFiles += name!!
        return name
    }

    /** Opens the seed the way the app opens spamless.db (Room 8 to 9) and runs the migration with that file. */
    private fun migrate(): MigrationResult {
        val opened = db ?: SpamDatabase.build(app, TEST_DB).also { db = it }
        val journal = app.getSharedPreferences(TEST_JOURNAL, Context.MODE_PRIVATE)
        // Only the test's own thread gets the test file's mover; any other caller gets the app's own.
        SpamProviderMigration.moverFor = { context ->
            if (Thread.currentThread() !== testThread) {
                originalMover(context)
            } else {
                val store = AndroidMigrationStore(context.applicationContext)
                OlderSpamMover(
                    rows = AndroidMigrationRows(context.applicationContext, opened),
                    probe = store,
                    writer = store,
                    journal = PrefsMigrationJournal(journal),
                    appVersion = BuildConfig.VERSION_CODE,
                )
            }
        }
        return SpamProviderMigration.migrate(app)
    }

    /** id to (mark, tries) of every held row. */
    private fun marks(): Map<Long, Pair<Long?, Int>> = db!!.openHelper.readableDatabase
        .query("SELECT id, migratedMsgId, migrateTries FROM blocked_messages").use { c ->
            buildMap {
                while (c.moveToNext()) put(c.getLong(0), (if (c.isNull(1)) null else c.getLong(1)) to c.getInt(2))
            }
        }

    private fun smsFrom(address: String): List<Pair<String, Stored>> = app.contentResolver.query(
        Sms.CONTENT_URI,
        arrayOf(Sms._ID, Sms.THREAD_ID, Sms.DATE, Sms.READ, Sms.SEEN, Sms.BODY),
        "${Sms.ADDRESS} = ? AND ${Sms.TYPE} = ?",
        arrayOf(address, "${Sms.MESSAGE_TYPE_INBOX}"),
        null,
    )!!.use { c ->
        buildList {
            while (c.moveToNext()) {
                add(c.getString(5) to Stored(c.getLong(0), c.getLong(1), c.getLong(2), c.getInt(3), c.getInt(4), null))
            }
        }
    }

    /** Received MMS of the seed's days whose sender is [address]. */
    private fun mmsFrom(address: String): List<Stored> = app.contentResolver.query(
        Mms.CONTENT_URI,
        arrayOf(Mms._ID, Mms.THREAD_ID, Mms.DATE, Mms.READ, Mms.SEEN, Mms.MESSAGE_ID),
        "${Mms.MESSAGE_BOX} = ? AND ${Mms.DATE} BETWEEN ? AND ?",
        arrayOf("${Mms.MESSAGE_BOX_INBOX}", "${base / 1000 - DAY_S}", "${base / 1000 + DAY_S}"),
        null,
    )!!.use { c ->
        buildList {
            while (c.moveToNext()) {
                add(Stored(c.getLong(0), c.getLong(1), c.getLong(2), c.getInt(3), c.getInt(4), c.getString(5)))
            }
        }
    }.filter { mmsSender(it.id) == address }

    private fun mmsSender(id: Long): String = app.contentResolver.query(
        Uri.parse("${Mms.CONTENT_URI}/$id/addr"), arrayOf(Mms.Addr.ADDRESS), "${Mms.Addr.TYPE} = ?",
        arrayOf("${PduHeaders.FROM}"), null,
    )!!.use { if (it.moveToFirst()) it.getString(0).orEmpty() else "" }

    private fun threadDate(threadId: Long): Long? = app.contentResolver.query(
        Threads.CONTENT_URI.buildUpon().appendQueryParameter("simple", "true").build(),
        arrayOf(Threads.DATE), "${Threads._ID} = ?", arrayOf("$threadId"), null,
    )?.use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun storeCounts(): Pair<Int, Int> = SENDERS.sumOf { smsFrom(it).size } to SENDERS.sumOf { mmsFrom(it).size }

    /** Every check of the class doc on the store and the marks after a run. */
    private fun assertEveryRowOnceInPlace() {
        val marks = marks()
        assertEquals("no held row lost from spamless.db", seed.size, marks.size)
        val (sms, mms) = storeCounts()
        assertEquals("SMS in the store", seed.count { it.goesAsText }, sms)
        assertEquals("MMS in the store", seed.count { it.goesAsMms }, mms)

        for (row in seed) {
            val (mark, tries) = marks.getValue(row.id)
            assertEquals("row ${row.id}: no failed try", 0, tries)
            val thread = Threads.getOrCreateThreadId(app, row.address)
            when {
                row.goesAsText -> {
                    val matches = smsFrom(row.address)
                        .filter { (body, stored) -> body == row.body && stored.date == row.at }
                    assertEquals("row ${row.id}: stored once", 1, matches.size)
                    val stored = matches.single().second
                    assertEquals("row ${row.id}: mark is its store id", stored.id, mark)
                    assertStoredAsSpam(row, stored, thread)
                }
                row.goesAsMms -> {
                    val matches = mmsFrom(row.address).filter { it.date == row.at / 1000 }
                    assertEquals("row ${row.id}: stored once", 1, matches.size)
                    val stored = matches.single()
                    assertEquals("row ${row.id}: mark is its store id", stored.id, mark)
                    assertStoredAsSpam(row, stored, thread)
                    row.messageId?.let { assertEquals("row ${row.id}: message id", it, stored.messageId) }
                }
                else -> {
                    assertEquals("row ${row.id}: nothing to move", MigrationMark.NOTHING_TO_MOVE, mark)
                    assertTrue("row ${row.id}: nothing written", smsFrom(row.address).isEmpty())
                    assertTrue("row ${row.id}: nothing written", mmsFrom(row.address).isEmpty())
                }
            }
        }

        // The threads are dated by their newest moved message, not by the time of the migration (step F finding 1).
        seed.filter { it.goesAsText || it.goesAsMms }.groupBy { it.address }.forEach { (address, rows) ->
            val newest = rows.maxOf { if (it.goesAsMms) it.at / 1000 * 1000 else it.at }
            val thread = Threads.getOrCreateThreadId(app, address)
            assertEquals("thread of row ${rows.first().id}: dated by its newest message", newest, threadDate(thread))
        }
    }

    private fun assertStoredAsSpam(row: Held, stored: Stored, thread: Long) {
        assertEquals("row ${row.id}: in its sender's thread", thread, stored.threadId)
        assertEquals("row ${row.id}: read", 1, stored.read)
        assertEquals("row ${row.id}: seen", 1, stored.seen)
    }

    @Test
    fun everyHeldRowIsInTheStoreOnceWithItsThreadAndDate() {
        seedVersion8()
        assertEquals("the store starts without the seed's senders", 0 to 0, storeCounts())

        val first = migrate()
        assertFalse("not waiting for the role", first.waiting)
        assertFalse("not halted", first.halted)
        assertEquals("no failed verify", 0, first.failed)
        assertEquals("nothing left", 0, first.left)
        assertEquals("rows moved", seed.count { it.goesAsText || it.goesAsMms }, first.moved)
        assertEveryRowOnceInPlace()

        // A second run (the next app start) finds everything done: nothing doubled, no mark changed.
        val marksBefore = marks()
        val second = migrate()
        assertEquals("nothing moved again", 0, second.moved)
        assertEquals("marks unchanged", marksBefore, marks())
        assertEveryRowOnceInPlace()
    }

    private companion object {
        const val TEST_DB = "j10-spamless-v8.db"
        const val TEST_JOURNAL = "j10_older_spam_test"
        const val APP_CLASS = "com.glooboo.umessage.App"
        const val MINUTE_MS = 60_000L
        const val DAY_S = 24L * 60 * 60
        const val THREE_DAYS_MS = 3 * DAY_S * 1000

        /** emulator-5554's own number, the "to" of a one-to-one MMS (as in the step F seed). */
        const val EMULATOR_SELF = "+15555215554"

        /** One made-up sender per held row; row 5 shares row 4's sender, so index 4 stays unused. */
        val SENDERS = (4101..4109).map { "+1555000$it" }
    }
}

/**
 * A minimal m-retrieve-conf, byte for byte as the step F seed script wrote it (seed_held.py): type, version, date,
 * from, to, an optional message id, then multipart/mixed with a text part and a 1x1 PNG. mmslib parses it and
 * persists it the way a downloaded MMS is persisted.
 */
internal object RetrieveConfPdu {
    private val PNG = (
        "89504e470d0a1a0a0000000d4948445200000001000000010806000000" +
            "1f15c4890000000d49444154789c6360000002000154a24f5d0000000049454e44ae426082"
        ).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    fun make(sender: String, to: List<String>, text: String, dateSeconds: Long, messageId: String?): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x8C.toByte(), 0x84.toByte(), 0x8D.toByte(), 0x90.toByte()))
        out.write(0x85)
        out.write(longInteger(dateSeconds))
        val from = byteArrayOf(0x80.toByte()) + text("$sender/TYPE=PLMN")
        out.write(0x89)
        out.write(from.size)
        out.write(from)
        to.forEach {
            out.write(0x97)
            out.write(text("$it/TYPE=PLMN"))
        }
        messageId?.let {
            out.write(0x8B)
            out.write(text(it))
        }
        out.write(byteArrayOf(0x84.toByte(), 0xA3.toByte()))
        val data = text.toByteArray(Charsets.UTF_8)
        val textType = byteArrayOf(0x03, 0x83.toByte(), 0x81.toByte(), 0xEA.toByte(), 0x8E.toByte()) + text("text.txt")
        val pngType = byteArrayOf(0xA0.toByte(), 0x8E.toByte()) + text("image.png")
        out.write(uintvar(2))
        out.write(part(textType, data))
        out.write(part(pngType, PNG))
        return out.toByteArray()
    }

    private fun part(type: ByteArray, data: ByteArray) = uintvar(type.size) + uintvar(data.size) + type + data

    private fun text(value: String) = value.toByteArray(Charsets.UTF_8) + 0

    private fun uintvar(value: Int): ByteArray {
        var rest = value ushr 7
        val bytes = ArrayDeque<Byte>().apply { addFirst((value and 0x7F).toByte()) }
        while (rest != 0) {
            bytes.addFirst((0x80 or (rest and 0x7F)).toByte())
            rest = rest ushr 7
        }
        return bytes.toByteArray()
    }

    private fun longInteger(value: Long): ByteArray {
        val digits = java.math.BigInteger.valueOf(value).toByteArray().dropWhile { it == 0.toByte() }
        val bytes = digits.ifEmpty { listOf(0.toByte()) }
        return byteArrayOf(bytes.size.toByte()) + bytes.toByteArray()
    }
}
