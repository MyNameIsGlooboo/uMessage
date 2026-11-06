package com.glooboo.umessage.activities

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.ProviderInfo
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.THREAD_FOCUS_COMPOSER
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.helpers.THREAD_NOTICE
import com.glooboo.umessage.helpers.THREAD_NUMBER
import com.glooboo.umessage.testing.FakeSmsMmsProvider
import com.glooboo.umessage.testing.FakeTelephonyProvider
import com.glooboo.umessage.testing.ShadowRecordingSmsManager
import com.glooboo.umessage.testing.joinAppBackgroundThreads
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

/** A chat opened from a blocked chat after Not spam (THREAD_FOCUS_COMPOSER) puts the cursor in the message field. */
@RunWith(AndroidJUnit4::class)
class ThreadFocusComposerTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<ThreadActivity>? = null

    @Before
    fun setUp() {
        ShadowRecordingSmsManager.reset()
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

    private fun settle() {
        repeat(2) {
            compose.waitForIdle()
            joinAppBackgroundThreads()
        }
        compose.waitForIdle()
    }

    private fun open(vararg extras: Pair<String, Any>) {
        val intent = Intent(app, ThreadActivity::class.java).putExtra(THREAD_ID, THREAD).putExtra(THREAD_NUMBER, "111")
        extras.forEach { (key, value) ->
            when (value) {
                is Boolean -> intent.putExtra(key, value)
                is String -> intent.putExtra(key, value)
            }
        }
        scenario = ActivityScenario.launch(intent)
        settle()
    }

    private fun vm(): ThreadViewModel {
        var vm: ThreadViewModel? = null
        scenario!!.onActivity { vm = ViewModelProvider(it)[ThreadViewModel::class.java] }
        return vm!!
    }

    @Test
    fun theComposerTakesFocusOnceAndTheNoticeShows() {
        open(THREAD_FOCUS_COMPOSER to true, THREAD_NOTICE to "Moved to inbox")
        assertThat(vm().loaded).isTrue()
        assertThat(vm().focusInput).isGreaterThan(0)
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.onNodeWithText("Moved to inbox").assertExists()

        // Taken off the intent: a recreation (rotation) does not ask for focus or the note again.
        var hasExtra = true
        scenario!!.onActivity {
            hasExtra = it.intent.hasExtra(THREAD_FOCUS_COMPOSER) || it.intent.hasExtra(THREAD_NOTICE)
        }
        assertThat(hasExtra).isFalse()
    }

    @Test
    fun withoutTheExtraNoNoticeShows() {
        open()
        assertThat(vm().loaded).isTrue()
        compose.onNodeWithText("Moved to inbox").assertDoesNotExist()
    }

    private companion object {
        const val THREAD = 1L // the id the fake thread lookup gives "111"
    }
}
