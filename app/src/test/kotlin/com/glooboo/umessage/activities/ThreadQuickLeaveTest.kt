package com.glooboo.umessage.activities

import android.Manifest
import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.provider.Telephony
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
import com.glooboo.umessage.testing.FakeSmsMmsProvider
import com.glooboo.umessage.testing.FakeTelephonyProvider
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
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
 * A chat opened and left at once (Back before the first load finished) still ends up read, in the provider and in
 * the saved list; a hidden chat the user never got past the lock for does not.
 */
@RunWith(AndroidJUnit4::class)
class ThreadQuickLeaveTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var sms: FakeSmsMmsProvider
    private var scenario: ActivityScenario<ThreadActivity>? = null

    @Before
    fun setUp() {
        HiddenChatsSession.reset()
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        Robolectric.buildContentProvider(FakeTelephonyProvider::class.java)
            .create(ProviderInfo().apply { authority = "mms-sms" })
        sms = FakeSmsMmsProvider.installSms()
        FakeSmsMmsProvider.installMms()
    }

    @After
    fun tearDown() {
        scenario?.close()
        HiddenChatsSession.reset()
        resetSharedMessagesDatabase()
    }

    private fun receiveUnread(): Long {
        val uri = app.contentResolver.insert(Telephony.Sms.CONTENT_URI, ContentValues().apply {
            put("thread_id", THREAD)
            put("address", "111")
            put("body", "Hello there")
            put("date", System.currentTimeMillis() - 60_000L)
            put("type", Telephony.Sms.MESSAGE_TYPE_INBOX)
            put("read", 0)
            put("seen", 0)
        })!!
        offMainThread {
            app.conversationsDB.insertOrUpdate(
                Conversation(
                    threadId = THREAD, snippet = "Hello there", date = 1, read = false, title = "111", photoUri = "",
                    isGroupConversation = false, phoneNumber = "111", unreadCount = 1,
                )
            )
        }
        return uri.lastPathSegment!!.toLong()
    }

    private fun isRead(id: Long) = sms.intColumn("sms", id, "read") == 1

    private fun savedRead() = offMainThread { app.conversationsDB.getConversationWithThreadId(THREAD)?.read }

    private fun openThread() {
        scenario = ActivityScenario.launch(
            Intent(app, ThreadActivity::class.java).putExtra(THREAD_ID, THREAD).putExtra(THREAD_NUMBER, "111")
        )
    }

    private fun idleFor(millis: Long) {
        val until = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < until) {
            Thread.sleep(50)
            ShadowLooper.idleMainLooper()
        }
    }

    @Test
    fun aChatLeftBeforeItsFirstLoadFinishesEndsRead() {
        val id = receiveUnread()
        openThread()
        // Back at once: the activity is no longer in front while the provider load may still be running.
        scenario!!.moveToState(Lifecycle.State.CREATED)

        compose.waitUntil(TIMEOUT) { isRead(id) && savedRead() == true }
    }

    @Test
    fun aChatFinishedAtOnceEndsRead() {
        val id = receiveUnread()
        openThread()
        scenario!!.moveToState(Lifecycle.State.DESTROYED)

        compose.waitUntil(TIMEOUT) { isRead(id) && savedRead() == true }
    }

    @Test
    fun aHiddenChatBehindTheLockIsNotMarkedRead() {
        app.baseConfig.isAppPasswordProtectionOn = true
        HiddenChats.get(app).hide(THREAD)
        val id = receiveUnread()

        openThread()
        scenario!!.moveToState(Lifecycle.State.CREATED)
        idleFor(2_000L)

        assertThat(isRead(id)).isFalse()
        assertThat(savedRead()).isFalse()
    }

    private companion object {
        const val THREAD = 1L // the id the fake thread lookup gives "111"
        const val TIMEOUT = 10_000L
    }
}
