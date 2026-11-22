package com.glooboo.umessage.interfaces

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Mark as unread" from the chat writes the saved row only, and the list shows that row as it is (J2-4: no full pass
 * reads the count again), so the row carries an unread count of at least one, never a count it already had lowered.
 */
@RunWith(AndroidJUnit4::class)
class ConversationsDaoMarkUnreadTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() = resetSharedMessagesDatabase()

    private fun row(threadId: Long, unread: Int) = Conversation(
        threadId = threadId, snippet = "Hello", date = 1, read = unread == 0, title = "Test chat", photoUri = "",
        isGroupConversation = false, phoneNumber = "+15550001234", unreadCount = unread,
    )

    private fun saved(threadId: Long) =
        offMainThread { context.conversationsDB.getConversationWithThreadId(threadId) }!!

    @Test
    fun aReadChatMarkedUnreadCountsOne() {
        offMainThread {
            context.conversationsDB.insertOrUpdate(row(1, unread = 0))
            context.conversationsDB.markUnread(1)
        }

        assertThat(saved(1).read).isFalse()
        assertThat(saved(1).unreadCount).isEqualTo(1)
    }

    @Test
    fun aChatWithUnreadTextsKeepsItsCount() {
        offMainThread {
            context.conversationsDB.insertOrUpdate(row(2, unread = 3))
            context.conversationsDB.markUnread(2)
        }

        assertThat(saved(2).unreadCount).isEqualTo(3)
    }
}
