package com.glooboo.umessage.activities

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.helpers.THREAD_NUMBER
import com.glooboo.umessage.testing.FakeTelephonyProvider
import com.glooboo.umessage.ui.components.displayNumber
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast

/** Starting a chat from the New conversation screen (MC-07, MC-08, MC-10). */
@RunWith(AndroidJUnit4::class)
class NewConversationActivityTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<NewConversationActivity>? = null

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun submit(typed: String) {
        scenario = ActivityScenario.launch(NewConversationActivity::class.java)
        compose.onNode(hasSetTextAction()).performTextInput(typed)
        // The button names the number as the app shows it ("555-0123"); the chat still gets what was typed.
        val start = app.getString(R.string.screens_new_start, displayNumber(app, typed))
        compose.onNodeWithContentDescription(start).performClick()
    }

    private fun waitFor(condition: () -> Boolean) {
        compose.waitUntil(5_000) {
            ShadowLooper.idleMainLooper()
            condition()
        }
    }

    @Test
    fun `a Persian number opens the chat with plain digits and Back goes to the list`() {
        FakeTelephonyProvider.install()
        submit("۰۷۱۲ ۳۴۵ ۶۷۸۹")

        waitFor { shadowOf(app).peekNextStartedActivity() != null }
        val started = shadowOf(app).nextStartedActivity
        assertThat(started.component?.className).isEqualTo(ThreadActivity::class.java.name)
        assertThat(started.getStringExtra(THREAD_NUMBER)).isEqualTo("07123456789")
        assertThat(started.getLongExtra(THREAD_ID, 0)).isGreaterThan(0L)
        // The picker closed, so Back from the chat lands on the conversation list.
        scenario!!.onActivity { assertThat(it.isFinishing).isTrue() }
    }

    @Test
    fun `a number Android can't make a chat for says so and stays on the picker`() {
        // No telephony provider: the thread-id lookup fails and returns 0.
        submit("5550123")

        val message = app.getString(R.string.new_conversation_invalid_number)
        waitFor { ShadowToast.getTextOfLatestToast() == message }
        assertThat(shadowOf(app).nextStartedActivity).isNull()
        scenario!!.onActivity { assertThat(it.isFinishing).isFalse() }
    }
}
