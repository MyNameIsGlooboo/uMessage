package com.glooboo.umessage.messaging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.getThreadId
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.testing.FakeTelephonyProvider
import com.glooboo.umessage.testing.ShadowRecordingSmsManager
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import com.klinker.android.send_message.Settings
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A group sent as separate texts lands in each recipient's own chat. Those chats must preview the new text in the
 * list, not the message before it (seen on the emulator: the time moved, the preview did not).
 */
@RunWith(AndroidJUnit4::class)
class SeparateGroupPreviewTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        ShadowRecordingSmsManager.reset()
        FakeTelephonyProvider.install()
    }

    @After
    fun tearDown() = resetSharedMessagesDatabase()

    @Test
    fun eachRecipientsChatPreviewsTheNewText() {
        val threads = listOf("111", "222").map { context.getThreadId(it) }
        offMainThread {
            // Both chats are in the list and their older messages are in the app's cache, as after the import.
            threads.forEachIndexed { i, thread ->
                context.messagesDB.insertOrUpdate(message(id = 10L + i, thread = thread, body = "Old message", date = 1000))
                context.conversationsDB.insertOrUpdate(conversation(thread, snippet = "Old message", date = 1000))
            }

            MessagingUtils(context).sendSmsMessage(
                text = "Dinner at 8?", addresses = linkedSetOf("111", "222"),
                subId = Settings.DEFAULT_SUBSCRIPTION_ID, requireDeliveryReport = false,
            )

            // The full pass then stores each chat's row from the phone: new time, new snippet.
            threads.forEach { thread ->
                context.conversationsDB.insertOrUpdate(conversation(thread, snippet = "Dinner at 8?", date = 2_000_000_000))
            }
        }

        assertThat(ShadowRecordingSmsManager.textSends.map { it.destination }).containsExactly("111", "222")
        val list = offMainThread { context.conversationsDB.getNonArchived() }
        threads.forEach { thread ->
            assertThat(list.single { it.threadId == thread }.snippet).isEqualTo("Dinner at 8?")
        }
    }

    private fun conversation(thread: Long, snippet: String, date: Int) = Conversation(
        threadId = thread, snippet = snippet, date = date, read = true, title = "Chat $thread", photoUri = "",
        isGroupConversation = false, phoneNumber = "$thread",
    )

    private fun message(id: Long, thread: Long, body: String, date: Int) = Message(
        id = id, body = body, type = 1, status = -1, participants = ArrayList(), date = date, read = true,
        threadId = thread, isMMS = false, attachment = null, senderPhoneNumber = "", senderName = "",
        senderPhotoUri = "", subscriptionId = -1,
    )
}
