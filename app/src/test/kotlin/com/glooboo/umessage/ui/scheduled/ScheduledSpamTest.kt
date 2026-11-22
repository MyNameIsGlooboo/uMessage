package com.glooboo.umessage.ui.scheduled

import android.app.Application
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Provider truth, step H (U9): the scheduled list (and its count in the menu) leaves out the scheduled messages of a
 * chat in Spam; they show in that chat only. Fake numbers only.
 */
@RunWith(AndroidJUnit4::class)
class ScheduledSpamTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = offMainThread {
        listOf(SPAM, SHOWN).forEach { thread ->
            val phone = number(thread)
            app.conversationsDB.insertOrUpdate(Conversation(thread, "", 0, true, phone, "", false, phone))
            app.messagesDB.insertOrUpdate(scheduled(thread))
        }
        app.conversationsDB.insertSpamThread(SpamThread(SPAM, number(SPAM), SpamCause.UNKNOWN, spamSince = 1L))
        Unit
    }

    @After
    fun tearDown() = resetSharedMessagesDatabase()

    private fun number(thread: Long) = "+155500012$thread"

    private fun scheduled(thread: Long) = Message(
        id = thread, body = "See you", type = Telephony.Sms.MESSAGE_TYPE_QUEUED, status = 0, participants = ArrayList(),
        date = 2_000_000_000, read = true, threadId = thread, isMMS = false, attachment = null,
        senderPhoneNumber = number(thread), senderName = "", senderPhotoUri = "", subscriptionId = 0,
        isScheduled = true,
    )

    private fun listedThreads(): List<Long> = offMainThread {
        val state = ScheduledStateReader(app).read()
        (state.notSent + state.days.flatMap { it.rows }).map { it.threadId }
    }

    @Test
    fun aScheduledMessageOfASpamChatIsNotListed() {
        assertThat(listedThreads()).containsExactly(SHOWN)
        assertThat(offMainThread { ScheduledStateReader(app).read().count }).isEqualTo(1)
    }

    private companion object {
        const val SPAM = 111L
        const val SHOWN = 112L
    }
}
