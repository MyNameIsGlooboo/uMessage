package com.glooboo.umessage.extensions

import android.app.role.RoleManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.testing.FakeThreadStore
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowRoleManager

/**
 * H-4 (provider truth, lead decision): the binned messages of a chat in Spam are left out of "Empty" and of the bin
 * counts, as the bin list leaves the chat out; the other chats are emptied as before. Fake numbers only.
 */
@RunWith(AndroidJUnit4::class)
class RecycleBinSpamTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: FakeThreadStore

    @Before
    fun setUp() {
        val roles = shadowOf(context.getSystemService(RoleManager::class.java)) as ShadowRoleManager
        roles.addAvailableRole(RoleManager.ROLE_SMS)
        roles.addHeldRole(RoleManager.ROLE_SMS)
        store = FakeThreadStore.install()
        store.addThread(CHAT, FRIEND)
        store.addThread(SPAM, SPAM_SENDER)
        store.addSms(1, CHAT, "Kept", dateMs = 1_700_000_001_000, address = FRIEND)
        store.addSms(2, CHAT, "Binned", dateMs = 1_700_000_002_000, address = FRIEND)
        store.addSms(3, SPAM, "Binned spam", dateMs = 1_700_000_003_000, address = SPAM_SENDER)
        offMainThread {
            listOf(CHAT to FRIEND, SPAM to SPAM_SENDER).forEach { (thread, number) ->
                context.messagesDB.insertOrIgnoreAll(context.getMessages(thread, includeScheduledMessages = false))
                context.conversationsDB.insertOrUpdate(
                    Conversation(thread, "snippet", 1_700_000_000, true, number, "", false, number),
                )
            }
            context.conversationsDB.insertSpamThread(SpamThread(SPAM, SPAM_SENDER, SpamCause.UNKNOWN, spamSince = 1L))
            context.moveMessageToRecycleBin(2, false)
            context.moveMessageToRecycleBin(3, false)
        }
    }

    @After
    fun tearDown() = resetSharedMessagesDatabase()

    private fun binEntries() = offMainThread { context.messagesDB.getAllRecycleBinEntries().map { it.id }.sorted() }

    @Test
    fun theCountsLeaveOutTheBinnedMessagesOfSpamChats() {
        assertThat(offMainThread { context.recycleBinEntryCount() }).isEqualTo(1)
        assertThat(offMainThread { context.messagesDB.getRecycleBinMessageCountOutsideSpam() }).isEqualTo(1)
    }

    @Test
    fun emptyLeavesTheBinnedMessagesOfSpamChatsAlone() {
        val result = offMainThread { context.emptyRecycleBinAndTidyConversations() }

        assertThat(result).isEqualTo(DeleteResult.DELETED)
        assertThat(binEntries()).containsExactly(3L)
        assertThat(offMainThread { store.smsIds() }).containsExactly(1L, 3L)
        assertThat(offMainThread { context.recycleBinEntryCount() }).isEqualTo(0)
    }

    private companion object {
        const val CHAT = 5L
        const val SPAM = 6L
        const val FRIEND = "+15550001235"
        const val SPAM_SENDER = "+15550001234"
    }
}
