package com.glooboo.umessage.spam

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.databases.MessagesDatabase
import com.glooboo.umessage.interfaces.ConversationsDao
import com.glooboo.umessage.spam.SortingWorld.Companion.CONTACT
import com.glooboo.umessage.spam.SortingWorld.Companion.SENDER
import com.glooboo.umessage.spam.SortingWorld.Companion.chat
import com.glooboo.umessage.spam.engine.SpamCause
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Lock privacy (J12): a chat that a sort moves into Spam on its own (here a deleted contact) loses its Messages
 * notification, so its sender, text and Reply action leave the shade and the lock screen. A chat that stays in
 * Messages keeps its notification. All numbers are made up.
 */
@RunWith(AndroidJUnit4::class)
class SortNotificationsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private lateinit var db: MessagesDatabase
    private lateinit var dao: ConversationsDao
    private val world = SortingWorld()

    private val sorter by lazy {
        ChatSorter(db, snapshot = { world.judge.snapshot() }, contactsReadable = { world.contactsReadable })
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, MessagesDatabase::class.java).allowMainThreadQueries().build()
        dao = db.ConversationsDao()
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Test", NotificationManager.IMPORTANCE_HIGH))
    }

    @After
    fun tearDown() = db.close()

    private fun notifyChat(threadId: Long) {
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentText("hello")
            .build()
        manager.notify(threadId.hashCode(), notification)
    }

    private fun shownIds() = manager.activeNotifications.map { it.id }

    @Test
    fun aChatASortMovesIntoSpamLosesItsMessagesNotification() {
        world.contacts += SENDER
        world.contacts += CONTACT
        dao.insertOrUpdate(chat(1, SENDER))
        dao.insertOrUpdate(chat(2, CONTACT))
        assertThat((sorter.recompute() as SortResult.Applied).entered).isEmpty()
        notifyChat(1)
        notifyChat(2)

        // The contact of chat 1 was deleted: the sort moves that chat into Spam, the other chat stays in Messages.
        world.contacts -= SENDER
        val result = sorter.recompute() as SortResult.Applied
        assertThat(result.entered).containsExactly(1L)

        SpamSorting.cancelEnteredNotifications(context, result) { dao.getSpamThread(it) != null }

        assertThat(shownIds()).containsExactly(2L.hashCode())
    }

    @Test
    fun aSortThatMovesNothingIntoSpamCancelsNothing() {
        dao.insertOrUpdate(chat(1, SENDER))
        sorter.recompute()
        notifyChat(1)

        world.allow(SENDER)
        val result = sorter.recompute() as SortResult.Applied
        assertThat(result.entered).isEmpty()

        SpamSorting.cancelEnteredNotifications(context, result) { dao.getSpamThread(it) != null }

        assertThat(shownIds()).containsExactly(1L.hashCode())
    }

    /**
     * The real path: [SpamSorting.published] cancels, reading the app's Spam table. A chat a later sort already took
     * out of Spam (here thread 2, which has no Spam row) keeps the notification of its new message.
     */
    @Test
    fun publishingASortCancelsOnlyTheChatsStillInSpam() {
        val appDao = MessagesDatabase.getInstance(context).ConversationsDao()
        offMainThread {
            appDao.deleteSpamThread(2)
            appDao.insertSpamThread(SpamThread(1, SENDER, SpamCause.UNKNOWN, spamSince = 1L))
        }
        notifyChat(1)
        notifyChat(2)

        val both = setOf(1L, 2L)
        val result = SortResult.Applied(added = 2, removed = 0, changed = 0, touched = both, entered = both)
        offMainThread { SpamSorting.published(context, result) }

        assertThat(shownIds()).containsExactly(2L.hashCode())
    }

    /** Room refuses the app's own database on the main thread, where Robolectric runs the test. */
    private fun offMainThread(block: () -> Unit) {
        var failure: Throwable? = null
        Thread { runCatching(block).onFailure { failure = it } }.apply { start() }.join()
        failure?.let { throw it }
    }

    private companion object {
        const val CHANNEL = "sort_notifications_test"
    }
}
