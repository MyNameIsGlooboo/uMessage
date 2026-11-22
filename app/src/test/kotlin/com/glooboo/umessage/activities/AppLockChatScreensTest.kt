package com.glooboo.umessage.activities

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.provider.Telephony
import android.view.View
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.helpers.HiddenChats
import com.glooboo.umessage.helpers.HiddenChatsSession
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.helpers.THREAD_NUMBER
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.testing.FakeSmsMmsProvider
import com.glooboo.umessage.testing.FakeTelephonyProvider
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.glooboo.umessage.ui.lock.LockScope
import com.glooboo.umessage.ui.lock.LockTestApp
import com.google.common.truth.Truth.assertThat
import org.fossify.commons.extensions.appLockManager
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.helpers.AppLockManager
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * M-SEC1: with the whole app locked, a chat and its details show nothing until the lock is passed, whether the chat is
 * a normal one, a hidden one or one in Spam; the chat gates never reveal the screen under the app lock. Once unlocked,
 * the screen shows again when it comes back to the front.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = LockTestApp::class)
class AppLockChatScreensTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<out Activity>? = null

    @Before
    fun setUp() {
        HiddenChatsSession.reset()
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        Robolectric.buildContentProvider(FakeTelephonyProvider::class.java)
            .create(ProviderInfo().apply { authority = "mms-sms" })
        FakeSmsMmsProvider.installSms()
        FakeSmsMmsProvider.installMms()
        app.baseConfig.isAppPasswordProtectionOn = true
        LockScope.set(app, LockScope.WholeApp)
        forgetAppLockManager()
        app.appLockManager.lock()
    }

    /** The commons AppLockManager is process-wide; it is made again for this test's app (see SpamChatLockTest). */
    private fun forgetAppLockManager() {
        AppLockManager::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }

    @After
    fun tearDown() {
        runCatching { scenario?.close() }
        app.baseConfig.isAppPasswordProtectionOn = false
        LockScope.set(app, LockScope.WholeApp)
        forgetAppLockManager()
        HiddenChatsSession.reset()
        resetSharedMessagesDatabase()
    }

    private enum class Kind { NORMAL, HIDDEN, SPAM }

    private fun receive(kind: Kind) {
        app.contentResolver.insert(Telephony.Sms.CONTENT_URI, ContentValues().apply {
            put("thread_id", THREAD)
            put("address", SENDER)
            put("body", "Hello there")
            put("date", System.currentTimeMillis() - 60_000L)
            put("type", Telephony.Sms.MESSAGE_TYPE_INBOX)
            put("read", 0)
            put("seen", 0)
        })
        offMainThread {
            app.conversationsDB.insertOrUpdate(
                Conversation(
                    threadId = THREAD, snippet = "Hello there", date = 1, read = false, title = SENDER, photoUri = "",
                    isGroupConversation = false, phoneNumber = SENDER, unreadCount = 1,
                ),
            )
            if (kind == Kind.SPAM) {
                app.conversationsDB.insertSpamThread(SpamThread(THREAD, SENDER, SpamCause.UNKNOWN, 1, 1L))
            }
        }
        if (kind == Kind.HIDDEN) HiddenChats.get(app).hide(THREAD)
    }

    private fun idleFor(millis: Long) {
        val until = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < until) {
            Thread.sleep(STEP_MS)
            ShadowLooper.idleMainLooper()
        }
    }

    private fun contentVisible(): Boolean {
        var visible = false
        scenario!!.onActivity { visible = it.findViewById<View>(android.R.id.content).visibility == View.VISIBLE }
        return visible
    }

    /** Opens [intent] locked, checks it shows nothing, passes the lock as LockActivity does, and checks it shows. */
    private fun lockedThenUnlocked(kind: Kind, intent: Intent) {
        receive(kind)
        scenario = ActivityScenario.launch<Activity>(intent)
        idleFor(SETTLE_MS)
        assertThat(contentVisible()).isFalse()

        // The lock screen was passed: the app unlocks and the hidden chats count it, then the screen is in front again.
        app.appLockManager.unlock()
        HiddenChatsSession.authenticated(System.currentTimeMillis())
        scenario!!.moveToState(Lifecycle.State.STARTED)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        idleFor(SETTLE_MS)
        assertThat(contentVisible()).isTrue()
    }

    private fun thread() =
        Intent(app, ThreadActivity::class.java).putExtra(THREAD_ID, THREAD).putExtra(THREAD_NUMBER, SENDER)

    private fun details() = Intent(app, ConversationDetailsActivity::class.java).putExtra(THREAD_ID, THREAD)

    @Test
    fun aNormalChat() = lockedThenUnlocked(Kind.NORMAL, thread())

    @Test
    fun aHiddenChat() = lockedThenUnlocked(Kind.HIDDEN, thread())

    @Test
    fun aSpamChat() = lockedThenUnlocked(Kind.SPAM, thread())

    @Test
    fun theDetailsOfANormalChat() = lockedThenUnlocked(Kind.NORMAL, details())

    @Test
    fun theDetailsOfAHiddenChat() = lockedThenUnlocked(Kind.HIDDEN, details())

    @Test
    fun theDetailsOfASpamChat() = lockedThenUnlocked(Kind.SPAM, details())

    private companion object {
        const val THREAD = 1L // the id the fake thread lookup gives the first number it sees
        const val SENDER = "+15550001234"
        const val SETTLE_MS = 2_000L
        const val STEP_MS = 50L
    }
}
