package com.glooboo.umessage.extensions

import android.app.Application
import android.content.ContentValues
import android.provider.Telephony.Mms
import android.provider.Telephony.Sms
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.testing.FakeSmsMmsProvider
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * DL-21: "Mark as unread" must not flip the whole history to unread. Only the newest incoming message becomes
 * unread, so the list badge shows 1 (or no number when there is no incoming message), like Google Messages.
 */
@RunWith(AndroidJUnit4::class)
class MarkThreadUnreadTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var sms: FakeSmsMmsProvider
    private lateinit var mms: FakeSmsMmsProvider

    @Before
    fun setUp() {
        sms = FakeSmsMmsProvider.installSms()
        mms = FakeSmsMmsProvider.installMms()
    }

    @After
    fun tearDown() {
        app.config.markedUnreadThreads = emptySet()
        resetSharedMessagesDatabase()
    }

    private fun insertSms(threadId: Long, type: Int, dateMillis: Long): Long {
        val values = ContentValues().apply {
            put(Sms.THREAD_ID, threadId)
            put(Sms.ADDRESS, "111")
            put(Sms.BODY, "hi")
            put(Sms.DATE, dateMillis)
            put(Sms.TYPE, type)
            put(Sms.READ, 1)
            put(Sms.SEEN, 1)
        }
        return app.contentResolver.insert(Sms.CONTENT_URI, values)!!.lastPathSegment!!.toLong()
    }

    private fun insertMms(threadId: Long, box: Int, dateSeconds: Long): Long {
        val values = ContentValues().apply {
            put(Mms.THREAD_ID, threadId)
            put(Mms.MESSAGE_BOX, box)
            put(Mms.DATE, dateSeconds)
            put(Mms.READ, 1)
            put(Mms.SEEN, 1)
        }
        return app.contentResolver.insert(Mms.CONTENT_URI, values)!!.lastPathSegment!!.toLong()
    }

    @Test
    fun onlyTheNewestIncomingSmsBecomesUnread() {
        val incoming = (1..300).map { insertSms(THREAD, Sms.MESSAGE_TYPE_INBOX, it * 1000L) }
        insertSms(THREAD, Sms.MESSAGE_TYPE_SENT, 999_999L) // newer, but sent by the user
        val otherThread = insertSms(OTHER, Sms.MESSAGE_TYPE_INBOX, 5_000_000L)

        offMainThread { app.markThreadMessagesUnread(THREAD) }

        assertThat(app.getUnreadCountsByThread()).containsExactly(THREAD, 1)
        assertThat(sms.intColumn("sms", incoming.last(), "read")).isEqualTo(0)
        assertThat(sms.intColumn("sms", otherThread, "read")).isEqualTo(1)
        assertThat(THREAD.toString() in app.config.markedUnreadThreads).isTrue()
    }

    @Test
    fun aNewerIncomingMmsIsTheOneMarked() {
        val smsId = insertSms(THREAD, Sms.MESSAGE_TYPE_INBOX, 10_000L)
        val mmsId = insertMms(THREAD, Mms.MESSAGE_BOX_INBOX, 20L) // seconds: newer than the sms

        offMainThread { app.markThreadMessagesUnread(THREAD) }

        assertThat(mms.intColumn("mms", mmsId, "read")).isEqualTo(0)
        assertThat(sms.intColumn("sms", smsId, "read")).isEqualTo(1)
        assertThat(app.getUnreadCountsByThread()).containsExactly(THREAD, 1)
    }

    @Test
    fun aThreadWithOnlySentMessagesIsFlaggedWithoutACount() {
        val sent = insertSms(THREAD, Sms.MESSAGE_TYPE_SENT, 1_000L)

        offMainThread { app.markThreadMessagesUnread(THREAD) }

        assertThat(sms.intColumn("sms", sent, "read")).isEqualTo(1)
        assertThat(app.getUnreadCountsByThread()).isEmpty()
        assertThat(THREAD.toString() in app.config.markedUnreadThreads).isTrue()
    }

    private companion object {
        const val THREAD = 42L
        const val OTHER = 43L
    }
}
