package com.glooboo.umessage.extensions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.testing.FakeThreadStore
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B3-5 (provider truth): "Delete forever" and "Empty the Recycle Bin" say why nothing was deleted, so the bin screen
 * can tell the user instead of staying silent.
 */
@RunWith(AndroidJUnit4::class)
class RecycleBinPurgeResultTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: FakeThreadStore

    @Before
    fun setUp() {
        store = FakeThreadStore.install()
        store.addThread(CHAT, FakeThreadStore.ADDRESS)
        (1..2L).forEach { store.addSms(it, CHAT, "Message $it", dateMs = 1_700_000_000_000 + it * 1_000) }
        offMainThread {
            context.messagesDB.insertOrIgnoreAll(context.getMessages(CHAT, includeScheduledMessages = false))
            context.conversationsDB.insertOrUpdate(
                Conversation(CHAT, "snippet", 1_700_000_000, true, "Chat", "", false, "+15550001234"),
            )
            context.moveMessageToRecycleBin(2, false)
        }
    }

    @After
    fun tearDown() = resetSharedMessagesDatabase()

    @Test
    fun notTheDefaultAppBothPurgesSaySo() {
        val deleteForever = offMainThread { context.deleteRecycleBinMessagesOfConversations(listOf(CHAT)) }
        val empty = offMainThread { context.emptyRecycleBinAndTidyConversations() }

        assertThat(deleteForever).isEqualTo(DeleteResult.NOT_DEFAULT)
        assertThat(empty).isEqualTo(DeleteResult.NOT_DEFAULT)
        assertThat(deleteForever.messageRes).isNotNull()
        assertThat(offMainThread { store.smsIds() }).containsExactly(1L, 2L)
    }

    private companion object {
        const val CHAT = 5L
    }
}
