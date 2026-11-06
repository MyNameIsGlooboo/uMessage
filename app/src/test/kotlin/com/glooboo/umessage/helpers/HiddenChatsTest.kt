package com.glooboo.umessage.helpers

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.StarredMessage
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.glooboo.umessage.ui.main.InboxFilter
import com.glooboo.umessage.ui.main.MainSearchViewModel
import com.glooboo.umessage.ui.main.ReceivedSms
import com.glooboo.umessage.ui.main.RecentCodes
import com.glooboo.umessage.ui.main.SearchShortcuts
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.fossify.commons.extensions.baseConfig
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** B06: a hidden chat stays out of every place that lists chats or messages until it is unlocked. */
@RunWith(AndroidJUnit4::class)
class HiddenChatsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val hiddenChats get() = HiddenChats.get(app)

    @Before
    fun setUp() {
        HiddenChatsSession.reset()
        app.baseConfig.isAppPasswordProtectionOn = true
        hiddenChats.hide(HIDDEN)
    }

    @After
    fun tearDown() {
        HiddenChatsSession.reset()
        resetSharedMessagesDatabase()
    }

    private fun message(id: Long, thread: Long, body: String) = Message(
        id = id, body = body, type = Telephony.Sms.MESSAGE_TYPE_INBOX, status = 0, participants = ArrayList(),
        date = id.toInt(), read = true, threadId = thread, isMMS = false, attachment = null,
        senderPhoneNumber = "+15550100", senderName = "", senderPhotoUri = "", subscriptionId = 0,
    )

    private fun conversation(thread: Long, title: String) = Conversation(
        threadId = thread, snippet = "", date = 100, read = true, title = title, photoUri = "",
        isGroupConversation = false, phoneNumber = "+1555010$thread",
    )

    @Test
    fun aHiddenChatIsOutOfSightUntilTheSessionIsUnlocked() {
        assertThat(hiddenChats.isHidden(HIDDEN)).isTrue()
        assertThat(hiddenChats.isHidden(SHOWN)).isFalse()
        assertThat(hiddenChats.hiddenIds()).containsExactly(HIDDEN)
        assertThat(hiddenChats.withoutHidden(listOf(HIDDEN, SHOWN)) { it }).containsExactly(SHOWN)

        HiddenChatsSession.unlock()

        assertThat(hiddenChats.isHidden(HIDDEN)).isFalse()
        assertThat(hiddenChats.hiddenIds()).isEmpty()
        // Notifications and shortcuts stay private even while the chats are unlocked.
        assertThat(hiddenChats.isMarked(HIDDEN)).isTrue()
    }

    @Test
    fun withoutAnAppLockNothingIsHidden() {
        app.baseConfig.isAppPasswordProtectionOn = false

        assertThat(hiddenChats.isHidden(HIDDEN)).isFalse()
        assertThat(hiddenChats.isMarked(HIDDEN)).isFalse()
        assertThat(hiddenChats.isChosen(HIDDEN)).isTrue()
    }

    @Test
    fun unhidingBringsTheChatBack() {
        hiddenChats.unhide(HIDDEN)

        assertThat(hiddenChats.isHidden(HIDDEN)).isFalse()
        assertThat(hiddenChats.chosenIds()).isEmpty()
    }

    @Test
    fun theUnlockEndsAfterTheLockTimeoutOnceTheAppWasLeft() {
        HiddenChatsSession.unlock()
        HiddenChatsSession.appLeft(1_000)
        HiddenChatsSession.appReturned(1_000 + 29_000, timeoutMs = 30_000)
        assertThat(HiddenChatsSession.isUnlocked()).isTrue()

        HiddenChatsSession.appLeft(100_000)
        HiddenChatsSession.appReturned(100_000 + 31_000, timeoutMs = 30_000)
        assertThat(HiddenChatsSession.isUnlocked()).isFalse()
    }

    @Test
    fun withTheTimeoutAtZeroLeavingTheAppLocksTheHiddenChats() {
        HiddenChatsSession.unlock()
        HiddenChatsSession.appLeft(1_000)
        HiddenChatsSession.appReturned(1_001, timeoutMs = 0)

        assertThat(HiddenChatsSession.isUnlocked()).isFalse()
    }

    @Test
    fun aPinEnteredAMomentAgoCountsAndAnOldOneDoesNot() {
        HiddenChatsSession.authenticated(10_000)

        assertThat(HiddenChatsSession.recentlyAuthenticated(15_000)).isTrue()
        assertThat(HiddenChatsSession.recentlyAuthenticated(25_000)).isFalse()
    }

    @Test
    fun searchFindsNothingOfAHiddenChatUntilItIsUnlocked() = runBlocking<Unit>(Dispatchers.IO) {
        app.conversationsDB.insertOrUpdate(conversation(HIDDEN, "Pizza secret"))
        app.conversationsDB.insertOrUpdate(conversation(SHOWN, "Pizza friend"))
        app.messagesDB.insertOrUpdate(message(1, HIDDEN, "pizza at nine"))
        app.messagesDB.insertOrUpdate(message(2, SHOWN, "pizza at ten"))
        val search = MainSearchViewModel(app, SavedStateHandle())

        assertThat(search.searchDatabase("pizza").map { it.threadId }.toSet()).containsExactly(SHOWN)

        HiddenChatsSession.unlock()
        assertThat(search.searchDatabase("pizza").map { it.threadId }.toSet()).containsExactly(HIDDEN, SHOWN)
    }

    @Test
    fun recentCodesLeaveOutAHiddenChat() {
        val now = 1_000_000L
        val received = listOf(
            ReceivedSms(HIDDEN, "BANK", "Your code is 482913", now - 1_000),
            ReceivedSms(SHOWN, "SHOP", "Your code is 771204", now - 2_000),
        )

        val codes = RecentCodes.pick(hiddenChats.withoutHidden(received) { it.threadId }, now)

        assertThat(codes.map { it.code }).containsExactly("771204")
    }

    @Test
    fun theLinksTileAndTheStarsLeaveOutAHiddenChat() = runBlocking<Unit>(Dispatchers.IO) {
        app.messagesDB.insertOrUpdate(message(1, HIDDEN, "see https://secret.example/a"))
        app.messagesDB.insertOrUpdate(message(2, SHOWN, "see https://friend.example/b"))
        val star = StarredMessage(3, false, HIDDEN, "+15550100", 3, 0, 1, "starred secret")
        app.starredDB.insert(star)
        app.starredDB.insert(star.copy(id = 4, threadId = SHOWN, snapshotBody = "starred friend"))

        val links = SearchShortcuts.load(app, InboxFilter.Links, emptyList())
        val starred = SearchShortcuts.load(app, InboxFilter.Starred, emptyList())

        assertThat(links.hits.map { it.threadId }).containsExactly(SHOWN)
        assertThat(starred.hits.map { it.threadId }).containsExactly(SHOWN)
    }

    @Test
    fun aNewTextInAHiddenChatShowsOnlyNewMessage() {
        val manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        offMainThread {
            NotificationHelper(app).showMessageNotification(
                messageId = 1L, isMms = false, address = "+15550100", body = "Your code is 482913",
                threadId = HIDDEN, bitmap = null, sender = "Ali",
            )
        }

        val n = shadowOf(manager).allNotifications.single()

        assertThat(n.title()).isEqualTo(app.getString(R.string.app_name))
        assertThat(n.text()).isEqualTo(app.getString(R.string.new_message))
        assertThat(n.actions).isNull()
        assertThat(n.visibility).isEqualTo(NotificationCompat.VISIBILITY_PRIVATE)
        assertThat(n.publicVersion.title()).isEqualTo(app.getString(R.string.app_name))
        assertThat(n.publicVersion.text()).isEqualTo(app.getString(R.string.new_message))
    }

    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    private companion object {
        const val HIDDEN = 41L
        const val SHOWN = 42L
    }
}
