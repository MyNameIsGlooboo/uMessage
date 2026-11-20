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
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.helpers.HiddenChatsSession
import com.glooboo.umessage.helpers.IS_LAUNCHED_FROM_SHORTCUT
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.helpers.THREAD_NUMBER
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.testing.FakeSmsMmsProvider
import com.glooboo.umessage.testing.FakeTelephonyProvider
import com.glooboo.umessage.testing.ProviderTruthFlagRule
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.glooboo.umessage.ui.lock.LockActivity
import com.glooboo.umessage.ui.lock.LockScope
import com.google.common.truth.Truth.assertThat
import org.fossify.commons.extensions.appLockManager
import org.fossify.commons.extensions.baseConfig
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper

/**
 * Provider truth, step C (U1): with the app lock on for Spam only and locked, a chat in Spam is a locked screen from
 * every entry: a notification or search (a plain intent), the launcher shortcut, "recent apps" and its details
 * screen. Nothing of it shows, its texts stay unread, and the lock is asked for. A chat in Messages opens as before.
 */
@RunWith(AndroidJUnit4::class)
class SpamChatLockTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val flag = ProviderTruthFlagRule(enabled = true)

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var sms: FakeSmsMmsProvider
    private var scenario: ActivityScenario<out Activity>? = null

    @Before
    fun setUp() {
        HiddenChatsSession.reset()
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        Robolectric.buildContentProvider(FakeTelephonyProvider::class.java)
            .create(ProviderInfo().apply { authority = "mms-sms" })
        sms = FakeSmsMmsProvider.installSms()
        FakeSmsMmsProvider.installMms()
        app.baseConfig.isAppPasswordProtectionOn = true
        LockScope.set(app, LockScope.ChosenParts)
        app.appLockManager.lock()
    }

    @After
    fun tearDown() {
        scenario?.close()
        app.baseConfig.isAppPasswordProtectionOn = false
        LockScope.set(app, LockScope.WholeApp)
        HiddenChatsSession.reset()
        resetSharedMessagesDatabase()
    }

    /** An unread text from [SENDER] in thread 1, cached, and in Spam when [spam]. */
    private fun receive(spam: Boolean): Long {
        val uri = app.contentResolver.insert(Telephony.Sms.CONTENT_URI, ContentValues().apply {
            put("thread_id", THREAD)
            put("address", SENDER)
            put("body", "Hello there")
            put("date", System.currentTimeMillis() - 60_000L)
            put("type", Telephony.Sms.MESSAGE_TYPE_INBOX)
            put("read", 0)
            put("seen", 0)
        })!!
        offMainThread {
            app.conversationsDB.insertOrUpdate(
                Conversation(
                    threadId = THREAD, snippet = "Hello there", date = 1, read = false, title = SENDER, photoUri = "",
                    isGroupConversation = false, phoneNumber = SENDER, unreadCount = 1,
                ),
            )
            if (spam) app.conversationsDB.insertSpamThread(SpamThread(THREAD, SENDER, SpamCause.UNKNOWN, 1, 1L))
        }
        return uri.lastPathSegment!!.toLong()
    }

    private fun isRead(id: Long) = sms.intColumn("sms", id, "read") == 1

    private fun threadIntent() =
        Intent(app, ThreadActivity::class.java).putExtra(THREAD_ID, THREAD).putExtra(THREAD_NUMBER, SENDER)

    private fun launch(intent: Intent) {
        scenario = ActivityScenario.launch<Activity>(intent)
        idleFor(SETTLE_MS)
    }

    private fun idleFor(millis: Long) {
        val until = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < until) {
            Thread.sleep(STEP_MS)
            ShadowLooper.idleMainLooper()
        }
    }

    /** Whether the screen's content is hidden, and whether it asked for the lock (Back only cancels: EXTRA_PART). */
    private fun coveredAndAsked(): Pair<Boolean, Boolean> {
        var result = false to false
        scenario!!.onActivity { activity ->
            val hidden = activity.findViewById<View>(android.R.id.content).visibility != View.VISIBLE
            val asked = shadowOf(activity).nextStartedActivityForResult?.intent?.let {
                it.component?.className == LockActivity::class.java.name &&
                    it.getBooleanExtra(LockActivity.EXTRA_PART, false)
            } == true
            result = hidden to asked
        }
        return result
    }

    @Test
    fun aSpamChatOpenedFromANotificationOrSearchIsLocked() {
        val id = receive(spam = true)
        launch(threadIntent())

        assertThat(coveredAndAsked()).isEqualTo(true to true)
        assertThat(isRead(id)).isFalse()
    }

    @Test
    fun aSpamChatOpenedFromTheLauncherShortcutIsLocked() {
        val id = receive(spam = true)
        launch(threadIntent().putExtra(IS_LAUNCHED_FROM_SHORTCUT, true))

        assertThat(coveredAndAsked()).isEqualTo(true to true)
        assertThat(isRead(id)).isFalse()
    }

    @Test
    fun aSpamChatBackFromRecentsAfterTheLockClosedIsLockedAgain() {
        receive(spam = true)
        app.appLockManager.unlock()
        launch(threadIntent())
        assertThat(coveredAndAsked().first).isFalse()

        // The lock closed while the chat sat in "recent apps"; the chat comes back to the front.
        app.appLockManager.lock()
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        idleFor(SETTLE_MS)

        assertThat(coveredAndAsked()).isEqualTo(true to true)
    }

    @Test
    fun theDetailsOfASpamChatAreLocked() {
        receive(spam = true)
        launch(Intent(app, ConversationDetailsActivity::class.java).putExtra(THREAD_ID, THREAD))

        assertThat(coveredAndAsked()).isEqualTo(true to true)
    }

    @Test
    fun aChatInMessagesOpensAsBefore() {
        val id = receive(spam = false)
        launch(threadIntent())

        assertThat(coveredAndAsked()).isEqualTo(false to false)
        compose.waitUntil(TIMEOUT) { isRead(id) }
    }

    private companion object {
        const val THREAD = 1L // the id the fake thread lookup gives the first number it sees
        const val SENDER = "+15550001234"
        const val SETTLE_MS = 2_000L
        const val STEP_MS = 50L
        const val TIMEOUT = 10_000L
    }
}
