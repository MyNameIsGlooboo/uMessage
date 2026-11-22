package com.glooboo.umessage.activities

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.pm.ProviderInfo
import android.net.Uri
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.HiddenChatsSession
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.helpers.THREAD_NUMBER
import com.glooboo.umessage.helpers.THREAD_TEXT
import com.glooboo.umessage.models.AttachmentSelection
import com.glooboo.umessage.testing.FakeSmsMmsProvider
import com.glooboo.umessage.testing.FakeTelephonyProvider
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.glooboo.umessage.ui.thread.ThreadPeopleController
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

/** M-UI5: the chat that "Done" in the people panel opens in place keeps the composer's text and attachments. */
@RunWith(AndroidJUnit4::class)
class ThreadPeopleSwitchTest {

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

    private val photo = AttachmentSelection(
        id = "content://media/external/images/media/7", uri = Uri.parse("content://media/external/images/media/7"),
        mimetype = "image/jpeg", filename = "photo.jpg", isPending = false,
    )

    private fun idle() {
        repeat(IDLE_ROUNDS) {
            Thread.sleep(STEP_MS)
            ShadowLooper.idleMainLooper()
        }
    }

    /** Opens chat 1 with a photo in the composer, then the people panel's intent for chat 2; returns the view model. */
    private fun switchWith(keepAttachments: Boolean): ThreadViewModel {
        val first = Intent(app, ThreadActivity::class.java).putExtra(THREAD_ID, 1L).putExtra(THREAD_NUMBER, SENDER)
        scenario = ActivityScenario.launch(first)
        idle()
        lateinit var vm: ThreadViewModel
        scenario!!.onActivity { activity ->
            vm = ViewModelProvider(activity)[ThreadViewModel::class.java]
            vm.addAttachment(photo)
            val next = Intent(app, ThreadActivity::class.java)
                .putExtra(THREAD_ID, 2L)
                .putExtra(THREAD_NUMBER, OTHER)
                .putExtra(THREAD_TEXT, "On my way")
                .putExtra(ThreadPeopleController.EXTRA_KEEP_ATTACHMENTS, keepAttachments)
            // As the system delivers it to the singleTop chat screen that is in front.
            Activity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
                .apply { isAccessible = true }
                .invoke(activity, next)
        }
        idle()
        return vm
    }

    @Test
    fun theOtherChatKeepsTheTextAndTheAttachments() {
        val vm = switchWith(keepAttachments = true)

        assertThat(vm.attachments.map { it.id }).containsExactly(photo.id)
        assertThat(vm.text.text.toString()).isEqualTo("On my way")
    }

    @Test
    fun withoutTheExtraAnotherChatStartsWithoutAttachments() {
        val vm = switchWith(keepAttachments = false)

        assertThat(vm.attachments).isEmpty()
    }

    private companion object {
        const val SENDER = "+15550001234"
        const val OTHER = "+15550001235"
        const val IDLE_ROUNDS = 20
        const val STEP_MS = 50L
    }
}
