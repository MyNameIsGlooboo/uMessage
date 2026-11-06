package com.glooboo.umessage.activities

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.ProviderInfo
import android.provider.Telephony
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.helpers.THREAD_NUMBER
import com.glooboo.umessage.testing.FakeSmsMmsProvider
import com.glooboo.umessage.testing.FakeTelephonyProvider
import com.glooboo.umessage.testing.ShadowRecordingSmsManager
import com.glooboo.umessage.testing.joinAppBackgroundThreads
import com.glooboo.umessage.testing.offMainThread
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

/**
 * Characterization tests for the chat screen's send path (TU-50 refactor): a send handed to the system empties the
 * composer (a refused one too: it leaves a failed row to retry), and a scheduled message keeps its time across recreation and is saved
 * instead of sent.
 */
@RunWith(AndroidJUnit4::class)
class ThreadSendFlowTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<ThreadActivity>? = null
    private lateinit var sms: FakeSmsMmsProvider

    @Before
    fun setUp() {
        ShadowRecordingSmsManager.reset()
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

    /**
     * Lets everything the screen started finish: its background threads, what they post back to the main thread,
     * and the recomposition after it. Waiting on that, not on wall-clock time, keeps these tests stable under load.
     */
    private fun settle() {
        // Twice: what a background thread posts back to the main thread may start another one.
        repeat(2) {
            compose.waitForIdle()
            joinAppBackgroundThreads()
        }
        compose.waitForIdle()
    }

    private fun openThread() {
        scenario = ActivityScenario.launch(
            Intent(app, ThreadActivity::class.java).putExtra(THREAD_ID, THREAD).putExtra(THREAD_NUMBER, "111")
        )
        settle()
        assertThat(vm().loaded).isTrue()
    }

    private fun vm(): ThreadViewModel {
        var vm: ThreadViewModel? = null
        scenario!!.onActivity { vm = ViewModelProvider(it)[ThreadViewModel::class.java] }
        return vm!!
    }

    private fun type(text: String) {
        scenario!!.onActivity { ViewModelProvider(it)[ThreadViewModel::class.java].text.setTextAndPlaceCursorAtEnd(text) }
        settle()
    }

    private fun composerText(): String = vm().text.text.toString()

    private fun tapSend(scheduled: Boolean = false) {
        val label = app.getString(if (scheduled) R.string.thread_send_scheduled else R.string.thread_send)
        compose.onNodeWithContentDescription(label).performClick()
        settle()
    }

    @Test
    fun sendHandedOffEmptiesTheComposer() {
        openThread()
        type("See you soon")

        tapSend()

        val sent = ShadowRecordingSmsManager.textSends.single()
        assertThat(sent.destination).isEqualTo("111")
        assertThat(sent.parts.joinToString("")).isEqualTo("See you soon")
        assertThat(composerText()).isEmpty()
    }

    /** A send the radio refuses still leaves a failed row (with Retry), so it counts as handed off. */
    @Test
    fun refusedSendLeavesAFailedRowAndEmptiesTheComposer() {
        ShadowRecordingSmsManager.failingDestinations += "111"
        openThread()
        type("Please keep me")

        tapSend()

        assertThat(sms.smsTypes().map { it.second }).contains(Telephony.Sms.MESSAGE_TYPE_FAILED)
        assertThat(ShadowRecordingSmsManager.textSends).isEmpty()
        assertThat(composerText()).isEmpty()
    }

    @Test
    fun scheduleSurvivesRecreationAndSendSavesInsteadOfSending() {
        openThread()
        val at = System.currentTimeMillis() + 3_600_000L
        scenario!!.onActivity { ViewModelProvider(it)[ThreadViewModel::class.java].scheduledMillis = at }

        scenario!!.recreate()
        settle()
        assertThat(vm().loaded).isTrue()

        assertThat(vm().scheduledMillis).isEqualTo(at)
        assertThat(vm().scheduledLabel).isNotNull()

        type("Happy new year")
        tapSend(scheduled = true)

        val scheduled = offMainThread { app.messagesDB.getAllScheduledMessages() }.single()
        assertThat(scheduled.body).isEqualTo("Happy new year")
        assertThat(scheduled.millis() / 1000).isEqualTo(at / 1000)
        assertThat(ShadowRecordingSmsManager.textSends).isEmpty()
        assertThat(composerText()).isEmpty()
        assertThat(vm().isScheduling).isFalse()
    }

    private companion object {
        const val THREAD = 1L // the id the fake thread lookup gives "111"
    }
}
