package com.glooboo.umessage.ui.conversations

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.databases.MessagesDatabase
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.ui.spam.SpamChatsPolicy
import com.glooboo.umessage.ui.spam.SpamChatsSource
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Provider truth, step C (3.4, C-7, C-13): the Spam tab's number is one COUNT query. It counts unknown senders with
 * unread texts (uMessage's own count or the store's) that came after the tab was last open; blocked senders, chats of
 * another sender (S9), group chats and chats hidden behind the app lock never count. It equals the list rule.
 */
@RunWith(AndroidJUnit4::class)
class SpamTabCountTest {

    private lateinit var db: MessagesDatabase
    private val now = 1_800_000_000_000L
    private val seenAt = now - 60_000L

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, MessagesDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun seed(
        id: Long,
        cause: SpamCause,
        unread: Int,
        ageMs: Long,
        read: Boolean = true,
        sender: String? = null,
    ) {
        val number = "+1555000130$id"
        db.ConversationsDao().insertOrUpdate(
            Conversation(
                threadId = id, snippet = "hi", date = ((now - ageMs) / 1000L).toInt(), read = read, title = number,
                photoUri = "", isGroupConversation = false, phoneNumber = number,
            ),
        )
        db.ConversationsDao().insertSpamThread(SpamThread(id, sender ?: number, cause, unread = unread, spamSince = 1L))
    }

    @Test
    fun theCountMatchesTheListRule() {
        seed(1, SpamCause.UNKNOWN, unread = 1, ageMs = 1_000L)
        seed(2, SpamCause.BLOCKED, unread = 3, ageMs = 1_000L)
        seed(3, SpamCause.UNKNOWN, unread = 1, ageMs = 120_000L)
        seed(4, SpamCause.UNKNOWN, unread = 0, ageMs = 1_000L, read = false)
        seed(5, SpamCause.UNKNOWN, unread = 0, ageMs = 1_000L)
        seed(6, SpamCause.UNKNOWN, unread = 2, ageMs = 1_000L, sender = "+15550009999")

        val counted = SpamTabCount.count(db, seenAt, hidden = emptySet())

        assertThat(counted).isEqualTo(2)
        assertThat(counted).isEqualTo(SpamChatsPolicy.tabCount(SpamChatsSource.load(db.ConversationsDao()), seenAt))
        // Opening the tab clears it.
        assertThat(SpamTabCount.count(db, now, hidden = emptySet())).isEqualTo(0)
    }

    @Test
    fun chatsHiddenBehindTheAppLockDoNotCount() {
        seed(1, SpamCause.UNKNOWN, unread = 1, ageMs = 1_000L)
        seed(2, SpamCause.UNKNOWN, unread = 1, ageMs = 1_000L)
        assertThat(SpamTabCount.count(db, seenAt, hidden = setOf(2L))).isEqualTo(1)
    }
}
