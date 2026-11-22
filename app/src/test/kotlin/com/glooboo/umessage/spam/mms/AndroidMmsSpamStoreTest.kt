package com.glooboo.umessage.spam.mms

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.engine.Judgement
import com.glooboo.umessage.spam.engine.SenderVerdict
import com.glooboo.umessage.spam.engine.SpamCause
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
 * chat (E-1), read and seen are written both ways (E-3), a chat that holds only a scheduled message is not empty
 * (E-12), and the Spam table on the real Room database (E-23): the lists' rule, count lowering, one sender in two
 * written forms, the explicit notice marks, and the order that keeps a new sender's first Spam MMS silent.
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

    @Test
    fun e23_noticeOf_readsTheReadMark() {
        assertThat(store.noticeOf(mms.addNotice(SENDER, read = true))?.read).isTrue()
        assertThat(store.noticeOf(mms.addNotice(SENDER, transactionId = "T-2"))?.read).isFalse()
    }

    @Test
    fun e22_theNoticeMarks_areKeptAndForgotten() {
        val notice = mms.addNotice(SENDER)

        store.markNotice(notice, NoticeMark.Counted(12L))
        assertThat(store.markOf(notice)).isEqualTo(NoticeMark.Counted(12L))
        store.markNotice(notice, NoticeMark.Skipped)
        assertThat(store.markOf(notice)).isEqualTo(NoticeMark.Skipped)
        store.markNotice(notice, null)
        assertThat(store.markOf(notice)).isNull()
    }

    @Test
    fun j3_aFailedSweepMark_keepsItsCount() {
        val notice = mms.addNotice(SENDER)

        store.markNotice(notice, NoticeMark.Failed(2))
        assertThat(store.markOf(notice)).isEqualTo(NoticeMark.Failed(2))
        store.markNotice(notice, NoticeMark.Counted(12L))
        assertThat(store.markOf(notice)).isEqualTo(NoticeMark.Counted(12L))
    }

    @Test
    fun e23_isListedInSpam_followsTheListsRule() = offMain {
        context.conversationsDB.insertOrUpdate(chat(40L, SENDER))
        context.conversationsDB.insertOrUpdate(chat(41L, SENDER, group = true))
        assertThat(store.isListedInSpam(40L)).isFalse()

        store.noteSpamArrival(40L, SENDER, SpamCause.UNKNOWN, 1_000L, raise = true)
        store.noteSpamArrival(41L, SENDER, SpamCause.UNKNOWN, 1_000L, raise = true)

        assertThat(store.isListedInSpam(40L)).isTrue()
        // A group chat (more than one recipient) is never listed in Spam, whatever its row says.
        assertThat(store.isListedInSpam(41L)).isFalse()
    }

    @Test
    fun e23_lowerSpamUnread_keepsCauseAndSpamSince_andNeverGoesBelowZero() = offMain {
        store.noteSpamArrival(50L, SENDER, SpamCause.BLOCKED, 1_000L, raise = true)
        store.noteSpamArrival(50L, SENDER, SpamCause.BLOCKED, 2_000L, raise = true)
        val before = context.conversationsDB.getSpamThread(50L)!!

        store.lowerSpamUnread(50L)
        val after = context.conversationsDB.getSpamThread(50L)!!
        assertThat(after.unread).isEqualTo(before.unread - 1)
        assertThat(after.cause).isEqualTo(SpamCause.BLOCKED)
        assertThat(after.spamSince).isEqualTo(before.spamSince)

        repeat(3) { store.lowerSpamUnread(50L) }
        assertThat(context.conversationsDB.getSpamThread(50L)!!.unread).isEqualTo(0)
    }

    @Test
    fun e23_noteSpamArrivalWithoutRaise_findsTheSenderInAnotherWrittenForm() = offMain {
        store.noteSpamArrival(60L, SENDER, SpamCause.UNKNOWN, 1_000L, raise = true)

        store.noteSpamArrival(60L, "(555) 000-1234", SpamCause.UNKNOWN, 2_000L, raise = false)

        val row = context.conversationsDB.getSpamThread(60L)!!
        assertThat(row.unread).isEqualTo(1)
        assertThat(row.sender).isEqualTo(SENDER)
    }

    @Test
    fun e23_aNewSendersFirstSpamMms_isListedInSpamAndSilent_inTheRealOrder() = offMain {
        val id = mms.addMms(threadId = 70L)
        val row = android.net.Uri.parse("content://mms/$id")
        // The real store, except that caching the chat writes the row the store's chat read would give.
        val cached = object : MmsSpamStore by store {
            override fun showChat(threadId: Long) {
                context.conversationsDB.insertOrUpdate(chat(threadId, SENDER))
            }
        }
        val check = object : MmsSenderCheck {
            override fun probe(): FilterDecision? = null
            override fun judge(): Judgement =
                Judgement(SenderVerdict.Spam(SpamCause.UNKNOWN, DecisionReason.UNKNOWN_SENDER))
            override fun historyChecked() = true
            override fun log(decision: String, reason: String, ruleId: Long?) = Unit
        }

        val place = IncomingMms(cached).placeDownloaded(row, 70L, SENDER, recipients = 1, check = check)

        assertThat(place).isEqualTo(MmsPlace.Spam(SpamCause.UNKNOWN))
        assertThat(context.conversationsDB.isInSpam(70L)).isTrue()
        assertThat(mms.value(id, "read")).isEqualTo(1L)
        assertThat(context.conversationsDB.getSpamThread(70L)!!.unread).isEqualTo(1)
    }

    private fun chat(thread: Long, number: String, group: Boolean = false) = Conversation(
        threadId = thread, snippet = "", date = 1, read = true, title = "", photoUri = "",
        isGroupConversation = group, phoneNumber = number, isScheduled = false,
    )

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
