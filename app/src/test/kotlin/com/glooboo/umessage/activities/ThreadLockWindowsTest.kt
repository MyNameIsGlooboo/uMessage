package com.glooboo.umessage.activities

import android.Manifest
import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.provider.Telephony
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.HiddenChatsSession
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.helpers.THREAD_NUMBER
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.testing.FakeSmsMmsProvider
import com.glooboo.umessage.testing.FakeTelephonyProvider
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.glooboo.umessage.ui.components.showConfirmDialog
import com.glooboo.umessage.ui.lock.LockScope
import com.glooboo.umessage.ui.lock.LockTestApp
import com.glooboo.umessage.ui.thread.ThreadViewModel
import com.google.common.truth.Truth.assertThat
import org.fossify.commons.extensions.appLockManager
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.helpers.AppLockManager
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * K2-6: the app lock hides the chat's content view while the lock screen is asked for (M-SEC1), but a sheet or dialog
 * draws in a window of its own. With the lock on, leaving the chat closes them; and the message field that had the
 * focus, which hiding the content takes away, gets it back once the lock is passed.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = LockTestApp::class)
class ThreadLockWindowsTest {

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
        forgetAppLockManager()
        app.baseConfig.isAppPasswordProtectionOn = true
        LockScope.set(app, LockScope.WholeApp)
        app.appLockManager.unlock()
    }

    /** The commons AppLockManager is process-wide; it is made again for this test's app (see SpamChatLockTest). */
    private fun forgetAppLockManager() {
        AppLockManager::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }

    @After
    fun tearDown() {
        runCatching { scenario?.close() }
        app.baseConfig.isAppPasswordProtectionOn = false
        LockScope.set(app, LockScope.WholeApp)
        forgetAppLockManager()
        HiddenChatsSession.reset()
        resetSharedMessagesDatabase()
    }

    @Test
    fun leavingTheChatUnderTheLockClosesItsSheetsAndDialogs() {
        openChat()
        openSheetAndDialog()

        scenario!!.moveToState(Lifecycle.State.CREATED) // Home: the chat is left

        assertThat(vm().pendingLink).isNull()
        assertThat(dialogHosts()).isEqualTo(0)
    }

    @Test
    fun withoutTheLockTheSheetsAndDialogsStay() {
        app.baseConfig.isAppPasswordProtectionOn = false
        openChat()
        openSheetAndDialog()

        scenario!!.moveToState(Lifecycle.State.CREATED)

        assertThat(vm().pendingLink).isEqualTo(LINK)
        assertThat(dialogHosts()).isEqualTo(1)
    }

    @Test
    fun theMessageFieldHasTheFocusAgainAfterTheLockIsPassed() {
        openChat()
        vm().focusInput++
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertIsFocused()

        // Away past the timeout: the chat comes back under the lock screen, its content hidden.
        app.appLockManager.lock()
        scenario!!.moveToState(Lifecycle.State.CREATED)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        assertThat(contentVisible()).isFalse()

        // The lock screen was passed: the chat is in front again.
        app.appLockManager.unlock()
        scenario!!.moveToState(Lifecycle.State.STARTED)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()

        assertThat(contentVisible()).isTrue()
        compose.onNode(hasSetTextAction()).assertIsFocused()
    }

    private fun openChat() {
        app.contentResolver.insert(Telephony.Sms.CONTENT_URI, ContentValues().apply {
            put("thread_id", THREAD)
            put("address", SENDER)
            put("body", TEXT)
            put("date", System.currentTimeMillis() - 60_000L)
            put("type", Telephony.Sms.MESSAGE_TYPE_INBOX)
            put("read", 1)
            put("seen", 1)
        })
        scenario = ActivityScenario.launch(
            Intent(app, ThreadActivity::class.java).putExtra(THREAD_ID, THREAD).putExtra(THREAD_NUMBER, SENDER)
        )
        assertThat(idleUntil { vm().items.filterIsInstance<Message>().any { it.body == TEXT } }).isTrue()
    }

    /** The link sheet (a view model state) and a confirm dialog (hosted in the content view) are open. */
    private fun openSheetAndDialog() {
        vm().pendingLink = LINK
        scenario!!.onActivity { it.showConfirmDialog(QUESTION, confirmLabel = "Yes", dismissLabel = "No") {} }
        ShadowLooper.idleMainLooper()
        assertThat(dialogHosts()).isEqualTo(1)
    }

    private fun vm(): ThreadViewModel {
        lateinit var vm: ThreadViewModel
        scenario!!.onActivity { vm = ViewModelProvider(it)[ThreadViewModel::class.java] }
        return vm
    }

    /** The 0x0 views `showComposeDialog` adds to the content, one per open dialog. */
    private fun dialogHosts(): Int {
        var hosts = 0
        scenario!!.onActivity { screen ->
            val content = screen.findViewById<ViewGroup>(android.R.id.content)
            hosts = (0 until content.childCount).map(content::getChildAt).count {
                it is ComposeView && it.layoutParams.width == 0 && it.layoutParams.height == 0
            }
        }
        return hosts
    }

    private fun contentVisible(): Boolean {
        var visible = false
        scenario!!.onActivity { visible = it.findViewById<View>(android.R.id.content).visibility == View.VISIBLE }
        return visible
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

    private companion object {
        const val THREAD = 1L // the id the fake thread lookup gives the first number it sees
        const val SENDER = "+15550001234"
        const val TEXT = "Hello there"
        const val LINK = "https://example.com"
        const val QUESTION = "Delete this message?"
        const val WAIT_MS = 10_000L
        const val STEP_MS = 20L
    }
}
