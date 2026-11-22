package com.glooboo.umessage.activities

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.provider.Telephony
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.HiddenChatsSession
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.helpers.THREAD_NUMBER
import com.glooboo.umessage.testing.FakeSmsMmsProvider
import com.glooboo.umessage.testing.FakeTelephonyProvider
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.glooboo.umessage.ui.thread.ThreadViewModel
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper

/** K2-1: a person's chat opened in place after a sender-name chat can be answered again. */
@RunWith(AndroidJUnit4::class)
class ThreadReplyDisabledSwitchTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<ThreadActivity>? = null

    @Before
    fun setUp() {
        HiddenChatsSession.reset()
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        Robolectric.buildContentProvider(FakeTelephonyProvider::class.java)
            .create(ProviderInfo().apply { authority = "mms-sms" })
        FakeSmsMmsProvider.installSms()
        FakeSmsMmsProvider.installMms()
    }

    @After
    fun tearDown() {
        runCatching { scenario?.close() }
        HiddenChatsSession.reset()
        resetSharedMessagesDatabase()
    }

    private fun receive(thread: Long, address: String, body: String) {
        app.contentResolver.insert(Telephony.Sms.CONTENT_URI, ContentValues().apply {
            put("thread_id", thread)
            put("address", address)
            put("body", body)
            put("date", System.currentTimeMillis() - 60_000L)
            put("type", Telephony.Sms.MESSAGE_TYPE_INBOX)
            put("read", 1)
            put("seen", 1)
        })
    }

    /** Runs the main looper until [done] holds, or the deadline passes; the chat loads on plain background threads. */
    private fun idleUntil(done: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            if (done()) return true
            Thread.sleep(STEP_MS)
        }
        return done()
    }

    @Test
    fun aPersonsChatOpenedAfterASenderNameChatCanBeAnswered() {
        receive(BANK_THREAD, BANK, "Your balance changed")
        receive(PERSON_THREAD, PERSON, "See you soon")
        scenario = ActivityScenario.launch(
            Intent(app, ThreadActivity::class.java).putExtra(THREAD_ID, BANK_THREAD).putExtra(THREAD_NUMBER, BANK)
        )
        lateinit var vm: ThreadViewModel
        scenario!!.onActivity { vm = ViewModelProvider(it)[ThreadViewModel::class.java] }
        // A sender name with letters cannot be answered: the composer shows the "reply disabled" bar.
        assertThat(idleUntil { vm.replyDisabled }).isTrue()

        // The person's notification is tapped: the chat screen in front loads that chat in place.
        scenario!!.onActivity { activity ->
            val next = Intent(app, ThreadActivity::class.java)
                .putExtra(THREAD_ID, PERSON_THREAD)
                .putExtra(THREAD_NUMBER, PERSON)
            Activity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
                .apply { isAccessible = true }
                .invoke(activity, next)
        }
        // The title is set after the participants and their reply check, at the end of the load.
        assertThat(idleUntil { vm.title.isNotEmpty() }).isTrue()

        assertThat(vm.replyDisabled).isFalse()
    }

    private companion object {
        const val BANK = "MyBank"
        const val PERSON = "+15550001234"
        const val BANK_THREAD = 1L
        const val PERSON_THREAD = 2L
        const val WAIT_MS = 10_000L
        const val STEP_MS = 20L
    }
}
