package com.glooboo.umessage.activities

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.ProviderInfo
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.extensions.getThreadId
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.helpers.THREAD_NUMBER
import com.glooboo.umessage.testing.FakeSmsMmsProvider
import com.glooboo.umessage.testing.FakeTelephonyProvider
import com.glooboo.umessage.testing.ShadowRecordingSmsManager
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.glooboo.umessage.ui.thread.ThreadRecipients
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

/** MC-13: starting a group conversation from the picker, and sending to everyone in it. */
@RunWith(AndroidJUnit4::class)
class GroupConversationTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: Application = ApplicationProvider.getApplicationContext()
    private var picker: ActivityScenario<NewConversationActivity>? = null
    private var thread: ActivityScenario<ThreadActivity>? = null

    @Before
    fun setUp() {
        ShadowRecordingSmsManager.reset()
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_PHONE_STATE)
    }

    @After
    fun tearDown() {
        picker?.close()
        thread?.close()
        resetSharedMessagesDatabase()
    }

    private fun waitFor(condition: () -> Boolean) {
        compose.waitUntil(TIMEOUT) {
            ShadowLooper.idleMainLooper()
            condition()
        }
    }

    private fun addTyped(number: String) {
        compose.onNode(hasSetTextAction()).performTextClearance()
        compose.onNode(hasSetTextAction()).performTextInput(number)
        compose.onNodeWithContentDescription(app.getString(R.string.screens_new_group_add, number)).performClick()
    }

    @Test
    fun pickingSeveralPeopleOpensOneThreadWithAllTheirNumbers() {
        FakeTelephonyProvider.install()
        picker = ActivityScenario.launch(NewConversationActivity::class.java)

        compose.onNodeWithText(app.getString(R.string.screens_new_create_group)).performClick()
        addTyped("111")
        addTyped("222")
        addTyped("333")
        // A chip removes its person; the same number typed twice is one person.
        compose.onNodeWithContentDescription(app.getString(R.string.screens_new_group_remove, "333")).performClick()
        addTyped("222")
        assertThat(compose.onAllNodesWithContentDescription(app.getString(R.string.screens_new_group_remove, "222"))
            .fetchSemanticsNodes()).hasSize(1)

        compose.onNodeWithText(app.getString(R.string.screens_new_group_next)).performClick()

        waitFor { shadowOf(app).peekNextStartedActivity() != null }
        val started = shadowOf(app).nextStartedActivity
        assertThat(started.component?.className).isEqualTo(ThreadActivity::class.java.name)
        assertThat(ThreadRecipients.numbersFromExtra(started.getStringExtra(THREAD_NUMBER))).containsExactly("111", "222").inOrder()
        assertThat(started.getLongExtra(THREAD_ID, 0)).isGreaterThan(0L)
    }

    @Test
    fun aGroupMessageGoesToEveryoneAsSeparateTextsWhenTheSettingSaysSo() {
        Robolectric.buildContentProvider(FakeTelephonyProvider::class.java)
            .create(ProviderInfo().apply { authority = "mms-sms" })
        FakeSmsMmsProvider.installSms()
        FakeSmsMmsProvider.installMms()
        app.config.sendGroupMessageMMS = false
        val threadId = offMainThread { app.getThreadId(setOf("111", "222")) }

        thread = ActivityScenario.launch(
            Intent(app, ThreadActivity::class.java)
                .putExtra(THREAD_ID, threadId)
                .putExtra(THREAD_NUMBER, "[\"111\",\"222\"]")
        )
        lateinit var vm: ThreadViewModel
        thread!!.onActivity { vm = ViewModelProvider(it)[ThreadViewModel::class.java] }
        waitFor { vm.loaded && vm.isGroup }
        assertThat(vm.groupSendMode).isFalse()
        compose.onNodeWithText(app.getString(R.string.thread_group_note_separate)).assertExists()

        thread!!.onActivity { vm.text.setTextAndPlaceCursorAtEnd("Dinner at eight") }
        compose.waitForIdle()
        compose.onNodeWithContentDescription(app.getString(R.string.thread_send)).performClick()

        waitFor { ShadowRecordingSmsManager.textSends.size >= 2 }
        assertThat(ShadowRecordingSmsManager.textSends.map { it.destination }).containsExactly("111", "222")
        assertThat(ShadowRecordingSmsManager.textSends.map { it.parts.joinToString("") }.distinct())
            .containsExactly("Dinner at eight")
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
