package com.glooboo.umessage.activities

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.ProviderInfo
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.extensions.messagesDB
import android.provider.Telephony.Sms.MESSAGE_TYPE_QUEUED
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.helpers.THREAD_NUMBER
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.testing.FakeSmsMmsProvider
import com.glooboo.umessage.testing.FakeTelephonyProvider
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/**
 * A message scheduled for a number with no conversation yet waits in a temporary thread. Opening the real
 * conversation with that number (from a link or the "not sent" notification) must show it, not "No messages yet".
 */
@RunWith(AndroidJUnit4::class)
class ScheduledForNewContactTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<ThreadActivity>? = null

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        Robolectric.buildContentProvider(FakeTelephonyProvider::class.java)
            .create(ProviderInfo().apply { authority = "mms-sms" })
        FakeSmsMmsProvider.installSms()
        FakeSmsMmsProvider.installMms()
    }

    @After
    fun tearDown() {
        scenario?.close()
        resetSharedMessagesDatabase()
    }

    private fun scheduleInTemporaryThread() {
        val participant = SimpleContact(1, 1, "111", "", arrayListOf(PhoneNumber("111", 0, "", "111")), ArrayList(), ArrayList())
        val message = Message(
            id = TEMP_THREAD, body = "See you at noon", type = MESSAGE_TYPE_QUEUED, status = -1,
            participants = arrayListOf(participant),
            date = ((System.currentTimeMillis() + 86_400_000L) / 1000).toInt(),
            read = false, threadId = TEMP_THREAD, isMMS = false, attachment = null,
            senderPhoneNumber = "", senderName = "", senderPhotoUri = "", subscriptionId = -1, isScheduled = true,
        )
        offMainThread {
            app.messagesDB.insertOrUpdate(message)
            app.conversationsDB.insertOrUpdate(
                Conversation(TEMP_THREAD, "See you at noon", message.date, true, "111", "", false, "111", isScheduled = true)
            )
        }
    }

    @Test
    fun realConversationShowsTheMessageScheduledBeforeItExisted() {
        scheduleInTemporaryThread()

        scenario = ActivityScenario.launch(
            Intent(app, ThreadActivity::class.java).putExtra(THREAD_ID, REAL_THREAD).putExtra(THREAD_NUMBER, "111")
        )

        compose.waitUntil(TIMEOUT) { compose.onAllNodesWithText("See you at noon").fetchSemanticsNodes().isNotEmpty() }
        val scheduled = offMainThread { app.messagesDB.getAllScheduledMessages() }
        assertThat(scheduled.map { it.threadId }).containsExactly(REAL_THREAD)
        assertThat(offMainThread { app.conversationsDB.getConversationWithThreadId(TEMP_THREAD) }).isNull()
    }

    private companion object {
        /** The id the fake thread lookup gives "111". */
        const val REAL_THREAD = 1L
        const val TEMP_THREAD = 1_700_000_000_123L
        const val TIMEOUT = 10_000L
    }
}
