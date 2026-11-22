package com.glooboo.umessage.spam.data

import android.provider.Telephony.Sms
import android.provider.Telephony.Threads
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.engine.SpamEngine
import com.glooboo.umessage.testing.DeviceTest
import com.glooboo.umessage.testing.SmsRole
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec 12.2, the provider round trip of a Spam insert, against the emulator's real telephony provider: a text whose
 * sender has a block rule goes the incoming path ([IncomingSmsProcessor.process]); the store keeps it read = 1 and
 * seen = 1 in the sender's own thread, and the app's list readers put that chat in Spam and not in Messages.
 *
 * Needs the SMS role (taken for the test, given back after). Emulator only. The sender is made up.
 */
@RunWith(AndroidJUnit4::class)
class SpamInsertRoundTripTest {

    private val app = DeviceTest.context
    private var role: SmsRole.Taken? = null
    private var ruleId = 0L

    @Before
    fun setUp() {
        DeviceTest.assumeEmulator()
        role = SmsRole.take()
        assumeTrue("uMessage must be the default SMS app to write to the store", SmsRole.isHeld())
        DeviceTest.forgetChats(listOf(SENDER))
        ruleId = runBlocking {
            SpamEngine.repository(app)
                .addRule(SpamRule(kind = ListKind.BLOCK, type = RuleType.NUMBER, pattern = SENDER))
        }
    }

    @After
    fun tearDown() {
        DeviceTest.forgetChats(listOf(SENDER))
        if (ruleId > 0L) runCatching { runBlocking { SpamEngine.repository(app).removeRule(ruleId) } }
        role?.restore()
    }

    @Test
    fun aSpamTextIsStoredReadAndSeenInItsThreadAndListedInSpamOnly() {
        val now = System.currentTimeMillis()
        val body = "J10 round trip ${now % 100_000}"
        val message = PendingSmsEntity(
            address = SENDER, subject = "", body = body, date = now, subscriptionId = -1, status = -1, createdAt = now,
        )

        IncomingSmsProcessor.process(app, message)

        // The store: exactly one inbox row, read and seen, in the sender's thread.
        val rows = app.contentResolver.query(
            Sms.CONTENT_URI,
            arrayOf(Sms.THREAD_ID, Sms.READ, Sms.SEEN, Sms.TYPE, Sms.DATE),
            "${Sms.ADDRESS} = ? AND ${Sms.BODY} = ?",
            arrayOf(SENDER, body),
            null,
        )!!.use { c ->
            buildList {
                while (c.moveToNext()) add(Stored(c.getLong(0), c.getInt(1), c.getInt(2), c.getInt(3), c.getLong(4)))
            }
        }
        assertEquals("one stored row", 1, rows.size)
        val row = rows.single()
        assertEquals("read", 1, row.read)
        assertEquals("seen", 1, row.seen)
        assertEquals("inbox", Sms.MESSAGE_TYPE_INBOX, row.type)
        assertEquals("the arrival time", now, row.date)
        assertEquals("the sender's own thread", Threads.getOrCreateThreadId(app, SENDER), row.threadId)
        val threadId = row.threadId

        // The app's readers: the chat is cached and listed in Spam, not in Messages (R3-1: cached at once or soon).
        val dao = app.conversationsDB
        assertTrue("listed in Spam", DeviceTest.waitFor { dao.getSpamList().any { it.threadId == threadId } })
        assertTrue("in Spam by the list rule", dao.isInSpam(threadId))
        assertFalse("not listed in Messages", dao.getMessagesList().any { it.threadId == threadId })
        assertEquals("unread count in Spam", 1, dao.getSpamThread(threadId)?.unread)
    }

    /** One store row as read above. */
    private data class Stored(val threadId: Long, val read: Int, val seen: Int, val type: Int, val date: Long)

    private companion object {
        const val SENDER = "+15550001234"
    }
}
