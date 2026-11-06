package com.glooboo.umessage.activities

import android.Manifest
import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.provider.Telephony
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.helpers.THREAD_NUMBER
import com.glooboo.umessage.models.Events
import com.glooboo.umessage.testing.FakeSmsMmsProvider
import com.glooboo.umessage.testing.FakeTelephonyProvider
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.greenrobot.eventbus.EventBus
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper

/** TU-11: a text that arrives while the chat is open but not in front stays unread until the user comes back. */
@RunWith(AndroidJUnit4::class)
class ThreadReadWhileAwayTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var sms: FakeSmsMmsProvider
    private var scenario: ActivityScenario<ThreadActivity>? = null

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        Robolectric.buildContentProvider(FakeTelephonyProvider::class.java)
            .create(ProviderInfo().apply { authority = "mms-sms" })
        sms = FakeSmsMmsProvider.installSms()
        FakeSmsMmsProvider.installMms()
    }

    @After
    fun tearDown() {
        scenario?.close()
        resetSharedMessagesDatabase()
    }

    private fun receive(body: String, secondsAgo: Int): Long {
        val uri = app.contentResolver.insert(Telephony.Sms.CONTENT_URI, ContentValues().apply {
            put("thread_id", THREAD)
            put("address", "111")
            put("body", body)
            put("date", System.currentTimeMillis() - secondsAgo * 1000L)
            put("type", Telephony.Sms.MESSAGE_TYPE_INBOX)
            put("read", 0)
            put("seen", 0)
        })!!
        return uri.lastPathSegment!!.toLong()
    }

    private fun isRead(id: Long) = sms.intColumn("sms", id, "read") == 1

    private fun openThread() {
        scenario = ActivityScenario.launch(
            Intent(app, ThreadActivity::class.java).putExtra(THREAD_ID, THREAD).putExtra(THREAD_NUMBER, "111")
        )
    }

    private fun waitForText(text: String) =
        compose.waitUntil(TIMEOUT) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun openingTheChatMarksItRead() {
        val first = receive("Hello there", secondsAgo = 60)
        openThread()
        waitForText("Hello there")
        compose.waitUntil(TIMEOUT) { isRead(first) }
    }

    @Test
    fun textArrivingWhileTheChatIsInTheBackgroundStaysUnread() {
        receive("Hello there", secondsAgo = 60)
        openThread()
        waitForText("Hello there")

        // The user switches to another app; the chat stays open behind it.
        scenario!!.moveToState(Lifecycle.State.CREATED)
        val code = receive("Your code is 4821", secondsAgo = 0)
        EventBus.getDefault().post(Events.RefreshMessages())
        // Let the refresh run to the end.
        repeat(20) {
            Thread.sleep(50)
            ShadowLooper.idleMainLooper()
        }
        assertThat(isRead(code)).isFalse()

        // Back in the chat: now it has been seen.
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(TIMEOUT) { isRead(code) }
    }

    private companion object {
        const val THREAD = 1L // the id the fake thread lookup gives "111"
        const val TIMEOUT = 10_000L
    }
}
