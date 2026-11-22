package com.glooboo.umessage.activities

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ProviderInfo
import android.os.Looper
import android.provider.Telephony
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
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
import com.glooboo.umessage.ui.thread.AttachKind
import com.glooboo.umessage.ui.thread.TapbackKind
import com.glooboo.umessage.ui.thread.TapbackReaction
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
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper
import java.time.Duration

/**
 * K2-6: the app lock hides the chat's content view while the lock screen is asked for (M-SEC1), but a sheet, menu or
 * dialog draws in a window of its own. With the lock on, leaving the chat closes them, except for a turn of the screen
 * or a picker of the chat's own; coming back locked closes what is still open; another chat loaded in place closes
 * them in any case (J5-5). The message field that had the focus gets it back once the lock is passed.
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
        assertThat(shownDialogs()).isGreaterThan(0)

        scenario!!.moveToState(Lifecycle.State.STARTED) // paused: Home or Recents is on its way
        compose.waitForIdle()

        assertThat(vm().pendingLink).isNull()
        assertThat(dialogHosts()).isEqualTo(0)
        assertThat(shownDialogs()).isEqualTo(0)
    }

    @Test
    fun withoutTheLockTheSheetsAndDialogsStay() {
        app.baseConfig.isAppPasswordProtectionOn = false
        openChat()
        openSheetAndDialog()

        scenario!!.moveToState(Lifecycle.State.STARTED)
        compose.waitForIdle()

        assertThat(vm().pendingLink).isEqualTo(LINK)
        assertThat(dialogHosts()).isEqualTo(1)
        assertThat(shownDialogs()).isGreaterThan(0)
    }

    /** A turn of the screen, or dark mode or the font size changing, is not leaving the chat. */
    @Test
    fun aRecreatedScreenKeepsTheSheet() {
        openChat()
        vm().pendingLink = LINK

        scenario!!.recreate()

        assertThat(vm().pendingLink).isEqualTo(LINK)
    }

    /** J5-7: the file picker of the attach sheet is the chat's own trip; what is open stays. */
    @Test
    fun aPickerOfTheChatsOwnKeepsTheSheet() {
        openChat()
        vm().pendingLink = LINK
        attach(AttachKind.File)

        scenario!!.moveToState(Lifecycle.State.CREATED)

        assertThat(vm().pendingLink).isEqualTo(LINK)
    }

    /** J5-2: a picker asked for long before (a prompt that never opened) does not excuse a later real exit. */
    @Test
    fun aPickerAskedLongBeforeDoesNotExcuseALaterExit() {
        openChat()
        vm().pendingLink = LINK
        attach(AttachKind.File)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(AppLockManager.OWN_TRIP_START_MS + 1_000L))

        scenario!!.moveToState(Lifecycle.State.CREATED)

        assertThat(vm().pendingLink).isNull()
    }

    /** J5-1: a picker trip that ends under the lock closes what it kept open, before the chat shows again. */
    @Test
    fun comingBackLockedClosesWhatAPickerTripKeptOpen() {
        openChat()
        vm().pendingLink = LINK
        attach(AttachKind.File)
        scenario!!.moveToState(Lifecycle.State.CREATED)
        assertThat(vm().pendingLink).isEqualTo(LINK)

        app.appLockManager.lock() // the screen went off meanwhile, or the trip took too long
        scenario!!.moveToState(Lifecycle.State.RESUMED)

        assertThat(vm().pendingLink).isNull()
        assertThat(contentVisible()).isFalse()
    }

    /** J5-7: the group question's guard is reset with its closed dialog, or Send would never ask again. */
    @Test
    fun theGroupQuestionCanBeAskedAgainAfterLeaving() {
        openChat()
        val guard = ThreadActivity::class.java.getDeclaredField("isGroupMessageConfirmationVisible")
            .apply { isAccessible = true }
        scenario!!.onActivity { guard.setBoolean(it, true) } // the question is open

        scenario!!.moveToState(Lifecycle.State.STARTED)

        scenario!!.onActivity { assertThat(guard.getBoolean(it)).isFalse() }
    }

    /** J5-7: a dialog kept in a composable's own state closes too: the reactions dialog. */
    @Test
    fun theReactionsDialogClosesWithTheWindows() {
        openChat()
        val message = vm().items.filterIsInstance<Message>().first()
        vm().reactions = mapOf(message.getStableId() to listOf(TapbackReaction(TapbackKind.Liked, 1, listOf(ORIGINAL))))
        compose.waitForIdle()
        // The chip's own click action, as TalkBack runs it; an injected touch did not open it under Robolectric.
        compose.onNode(hasContentDescription(TapbackKind.Liked.emoji) and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        compose.onNodeWithText(ORIGINAL).assertExists()

        vm().closeWindows()
        compose.waitForIdle()

        compose.onNodeWithText(ORIGINAL).assertDoesNotExist()
    }

    /** J5-3: the overflow menu closes with the windows. */
    @Test
    fun theOverflowMenuClosesWithTheWindows() {
        openChat()
        compose.onNodeWithContentDescription(app.getString(R.string.thread_more_options)).performClick()
        compose.waitForIdle()
        compose.onAllNodes(isPopup()).assertCountEquals(1)

        vm().closeWindows()
        compose.waitForIdle()

        compose.onAllNodes(isPopup()).assertCountEquals(0)
    }

    /** Reviewer 6: a notification tapped from the shade with the lock on: paused, the new intent, resumed. */
    @Test
    fun aChatLoadedInPlaceUnderTheLockOpensWithNoOldWindow() {
        openChat()
        openSheetAndDialog()

        scenario!!.moveToState(Lifecycle.State.STARTED)
        switchTo(SHOP_THREAD, SHOP)
        scenario!!.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()

        assertThat(vm().pendingLink).isNull()
        assertThat(dialogHosts()).isEqualTo(0)
        assertThat(shownDialogs()).isEqualTo(0)
        assertThat(idleUntil { vm().items.filterIsInstance<Message>().any { it.body == SHOP_TEXT } }).isTrue()
    }

    /** J5-5: with the lock off, the old chat's dialogs do not stay over a chat loaded in place. */
    @Test
    fun withoutTheLockAChatLoadedInPlaceClosesTheOldWindows() {
        app.baseConfig.isAppPasswordProtectionOn = false
        openChat()
        openSheetAndDialog()

        switchTo(SHOP_THREAD, SHOP)
        compose.waitForIdle()

        assertThat(vm().pendingLink).isNull()
        assertThat(dialogHosts()).isEqualTo(0)
        assertThat(shownDialogs()).isEqualTo(0)
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

    private fun openChat() {
        receive(THREAD, SENDER, TEXT)
        receive(SHOP_THREAD, SHOP, SHOP_TEXT)
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
        compose.waitForIdle()
        assertThat(dialogHosts()).isEqualTo(1)
    }

    /** An entry of the attach sheet, as tapped: the picker opens through the chat's result launcher. */
    private fun attach(kind: AttachKind) {
        scenario!!.onActivity { screen ->
            ThreadActivity::class.java.getDeclaredMethod("handleAttach", AttachKind::class.java)
                .apply { isAccessible = true }
                .invoke(screen, kind)
        }
    }

    /** A notification for another chat is tapped: the chat screen loads it in place. */
    private fun switchTo(thread: Long, number: String) {
        val next = Intent(app, ThreadActivity::class.java).putExtra(THREAD_ID, thread).putExtra(THREAD_NUMBER, number)
        scenario!!.onActivity { screen ->
            Activity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
                .apply { isAccessible = true }
                .invoke(screen, next)
        }
    }

    private fun vm(): ThreadViewModel {
        lateinit var vm: ThreadViewModel
        scenario!!.onActivity { vm = ViewModelProvider(it)[ThreadViewModel::class.java] }
        return vm
    }

    /** The dialog windows (sheets and dialogs alike) that are showing now. */
    private fun shownDialogs(): Int = ShadowDialog.getShownDialogs().count { it.isShowing }

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
        const val SHOP_THREAD = 2L
        const val SENDER = "+15550001234"
        const val SHOP = "MyShop"
        const val TEXT = "Hello there"
        const val SHOP_TEXT = "Your order is ready"
        const val ORIGINAL = "Liked “Hello there”"
        const val LINK = "https://example.com"
        const val QUESTION = "Delete this message?"
        const val WAIT_MS = 10_000L
        const val STEP_MS = 20L
    }
}
