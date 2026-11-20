package com.glooboo.umessage.spam.mms

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.testing.FakeMmsProvider
import com.glooboo.umessage.testing.FakeTelephonyProvider
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [AndroidMmsSpamStore] on stand-ins of the phone's store (step E): a notice saved for an automatic download has no
 * chat (E-1), read and seen are written both ways (E-3), and a chat that holds only a scheduled message is not empty
 * (E-12).
 */
@RunWith(AndroidJUnit4::class)
class AndroidMmsSpamStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var mms: FakeMmsProvider
    private lateinit var sms: FakeTelephonyProvider
    private val store by lazy { AndroidMmsSpamStore(context) }

    @Before
    fun setUp() {
        mms = FakeMmsProvider.install()
        sms = FakeTelephonyProvider.install()
    }

    @After
    fun tearDown() {
        resetSharedMessagesDatabase()
    }

    @Test
    fun e1_aNoticeSavedForAnAutomaticDownload_hasMmslibsDummyThread_whichIsNoChat() {
        val notice = mms.addNotice(SENDER)

        val row = store.noticeOf(notice)!!

        assertThat(row.sender).isEqualTo(SENDER)
        assertThat(row.threadId).isEqualTo(Long.MAX_VALUE)
        assertThat(IncomingMms.hasChat(row.threadId)).isFalse()
    }

    @Test
    fun aNoticeThatIsGone_isNull() {
        assertThat(store.noticeOf(android.net.Uri.parse("content://mms/inbox/999"))).isNull()
    }

    @Test
    fun e3_setThread_writesReadAndSeenBothWays() {
        val notice = mms.addNotice(SENDER, read = true)
        val id = notice.lastPathSegment!!.toLong()

        assertThat(store.setThread(notice, 12L, silent = false)).isTrue()
        assertThat(mms.value(id, "thread_id")).isEqualTo(12L)
        assertThat(mms.value(id, "read")).isEqualTo(0L)
        assertThat(mms.value(id, "seen")).isEqualTo(0L)

        store.setThread(notice, 13L, silent = true)
        assertThat(mms.value(id, "thread_id")).isEqualTo(13L)
        assertThat(mms.value(id, "read")).isEqualTo(1L)
        assertThat(mms.value(id, "seen")).isEqualTo(1L)

        // Without a chat the chat stays; only read and seen change.
        store.setThread(notice, null, silent = false)
        assertThat(mms.value(id, "thread_id")).isEqualTo(13L)
        assertThat(mms.value(id, "read")).isEqualTo(0L)
    }

    @Test
    fun hasMessages_readsTheSmsAndTheMmsOfTheChat() {
        assertThat(store.hasMessages(20L)).isFalse()

        mms.addMms(threadId = 20L)
        sms.addSms(SENDER, "hello", 1_000L, threadId = 21L)

        assertThat(store.hasMessages(20L)).isTrue()
        assertThat(store.hasMessages(21L)).isTrue()
        assertThat(store.hasMessages(22L)).isFalse()
    }

    @Test
    fun e12_aChatWithOnlyAScheduledMessage_hasOne() {
        // Room refuses the main thread; the store is only called from the MMS worker, so read it off main here too.
        assertThat(offMain { store.hasScheduled(30L) }).isFalse()

        offMain { context.messagesDB.insertOrUpdate(scheduled(id = 1_500_000_001L, thread = 30L)) }

        assertThat(offMain { store.hasScheduled(30L) }).isTrue()
        assertThat(offMain { store.hasScheduled(31L) }).isFalse()
    }

    private fun <T> offMain(block: () -> T): T {
        var result: Result<T>? = null
        val worker = Thread { result = runCatching(block) }
        worker.start()
        worker.join(10_000)
        return checkNotNull(result) { "worker timed out" }.getOrThrow()
    }

    private fun scheduled(id: Long, thread: Long) = Message(
        id = id, body = "later", type = 4, status = -1, participants = ArrayList(), date = 1000, read = true,
        threadId = thread, isMMS = false, attachment = null, senderPhoneNumber = "", senderName = "",
        senderPhotoUri = "", subscriptionId = 0, isScheduled = true,
    )

    private companion object {
        const val SENDER = "+15550001234"
    }
}
