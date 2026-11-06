package com.glooboo.umessage.activities

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.ProviderInfo
import android.provider.Telephony
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.testing.FakeSmsMmsProvider
import com.glooboo.umessage.testing.FakeTelephonyProvider
import com.glooboo.umessage.testing.ShadowRecordingSmsManager
import com.glooboo.umessage.testing.joinAppBackgroundThreads
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
 * RM-25: a scheduled message whose send failed is held (type FAILED, still scheduled). The thread screen must show
 * it as not sent with a Retry, and Retry sends it now and removes the held row.
 */
@RunWith(AndroidJUnit4::class)
class HeldScheduledMessageTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val failedLabel get() = app.getString(R.string.thread_status_failed)
    private var scenario: ActivityScenario<ThreadActivity>? = null

    @Before
    fun setUp() {
        // A test class that ran earlier may have left the shared database built on its own, now deleted, folder.
        resetSharedMessagesDatabase()
        ShadowRecordingSmsManager.reset()
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        // Only the thread-id lookup from this fake; sms and mms rows come from FakeSmsMmsProvider.
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

    private fun holdBirthdayMessage(): Message {
        val message = Message(
            id = 7001L, body = "Happy birthday!", type = Telephony.Sms.MESSAGE_TYPE_FAILED,
            status = Telephony.Sms.STATUS_NONE,
            participants = arrayListOf(
                SimpleContact(1, 1, "Sara", "", arrayListOf(PhoneNumber("111", 0, "", "111")), ArrayList(), ArrayList())
            ),
            // Its time is long past: the send failed and nothing may resend it by itself.
            date = ((System.currentTimeMillis() - 86_400_000L) / 1000).toInt(),
            read = true, threadId = THREAD, isMMS = false, attachment = null,
            senderPhoneNumber = "", senderName = "", senderPhotoUri = "", subscriptionId = -1, isScheduled = true,
        )
        offMainThread { app.messagesDB.insertOrUpdate(message) }
        return message
    }

    /**
     * Lets everything the screen started finish: its background threads (plain threads and EventBus's async pool),
     * what they post back to the main thread, and the recomposition after it, round after round until a round finds
     * nothing left running. Each step of the screen starts a new thread from the main thread. Checking the final
     * state, instead of polling until a condition holds once, is what caught the bug behind this test's timeouts.
     */
    private fun settle() {
        var rounds = 0
        do {
            compose.waitForIdle()
            val busy = joinAppBackgroundThreads(timeoutMs = SETTLE_GUARD_MS)
            check(++rounds < MAX_ROUNDS) { "The thread screen kept starting background work" }
        } while (busy)
        compose.waitForIdle()
    }

    private fun openThread() {
        scenario = ActivityScenario.launch(Intent(app, ThreadActivity::class.java).putExtra(THREAD_ID, THREAD))
        settle()
    }

    private fun failedShown() = compose.onAllNodesWithText(failedLabel).fetchSemanticsNodes().isNotEmpty()

    private fun scheduledIds() = offMainThread { app.messagesDB.getAllScheduledMessages().map { it.id } }

    @Test
    fun heldScheduledMessageShowsRetry() {
        holdBirthdayMessage()

        openThread()

        assertThat(failedShown()).isTrue()
        compose.onNodeWithText("Happy birthday!").assertExists()
        // Opening the thread must not throw the held message away as an expired schedule.
        assertThat(scheduledIds()).containsExactly(7001L)
    }

    @Test
    fun retrySendsTheHeldMessageAndClearsTheMark() {
        holdBirthdayMessage()
        openThread()
        assertThat(failedShown()).isTrue()

        compose.onNodeWithText(failedLabel).performClick()
        settle()

        // Retry starts two threads: one deletes the scheduled row, the refresh after the send moves the thread's
        // scheduled messages from a snapshot. That move re-inserted the deleted row when it came second, so the
        // held message came back (in the provider's thread) and polling for it to be gone timed out.
        assertThat(scheduledIds()).isEmpty()
        val sent = ShadowRecordingSmsManager.textSends.single()
        assertThat(sent.destination).isEqualTo("111")
        assertThat(sent.parts.joinToString("")).isEqualTo("Happy birthday!")
    }

    private companion object {
        const val THREAD = 5L
        /** Only a guard against a hung thread: the waits end as soon as the work does. */
        const val SETTLE_GUARD_MS = 60_000L
        const val MAX_ROUNDS = 50
    }
}
