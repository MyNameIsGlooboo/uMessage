package com.glooboo.umessage.ui.lock

import android.app.Activity
import android.app.Application
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.AppLockGuard
import com.glooboo.umessage.activities.CopyCodeActivity
import com.glooboo.umessage.helpers.HiddenChatsSession
import org.fossify.commons.extensions.appLockManager
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.helpers.AppLockManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [AppLockGuard]'s start and stop counting: backing out of the last screen locks the app and the hidden chats again,
 * while a screen that is only hidden, or one of several, does not. The session timing itself is in HiddenChatsTest.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = LockTestApp::class)
class AppLockBackOutTest {

    @Before
    @After
    fun freshState() {
        // The lock manager and the guard's screen counter are process-wide; each test starts and leaves them clean.
        AppLockManager::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        AppLockGuard::class.java.getDeclaredField("started").apply { isAccessible = true }.setInt(AppLockGuard, 0)
        HiddenChatsSession.reset()
    }

    private fun screen(): Activity = Robolectric.buildActivity(Activity::class.java).create().start().get().also {
        it.baseConfig.isAppPasswordProtectionOn = true
        it.appLockManager.unlock()
        HiddenChatsSession.unlock()
    }

    @Test
    fun backingOutOfTheLastScreenLocksTheAppAndTheHiddenChats() {
        val activity = screen()
        AppLockGuard.onActivityStarted(activity)
        activity.finish()

        AppLockGuard.onActivityStopped(activity)

        assertTrue(activity.appLockManager.isLocked())
        assertFalse(HiddenChatsSession.isUnlocked())
    }

    /**
     * M-T5: the same through the real lifecycle. The guard is registered as the app registers it, the only screen is
     * closed with Back (the chat's Back button finishes it), and the next screen that opens asks for the lock first.
     */
    @Test
    fun backingOutThroughTheLifecycleLocksAndTheNextScreenAsksForTheLock() {
        val app: Application = ApplicationProvider.getApplicationContext()
        app.registerActivityLifecycleCallbacks(AppLockGuard)
        try {
            val first = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            val activity = first.get()
            activity.baseConfig.isAppPasswordProtectionOn = true
            activity.appLockManager.unlock()

            activity.finish()
            first.pause().stop().destroy()

            assertTrue(activity.appLockManager.isLocked())
            val next = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
            assertEquals(LockActivity::class.java.name, shadowOf(next).nextStartedActivity?.component?.className)
        } finally {
            app.unregisterActivityLifecycleCallbacks(AppLockGuard)
            app.baseConfig.isAppPasswordProtectionOn = false
        }
    }

    @Test
    fun aScreenHiddenBehindAnotherAppDoesNotLock() {
        val activity = screen()
        AppLockGuard.onActivityStarted(activity)

        AppLockGuard.onActivityStopped(activity) // stopped, not finishing: Home, or another app on top

        assertFalse(activity.appLockManager.isLocked())
        assertTrue(HiddenChatsSession.isUnlocked())
    }

    @Test
    fun closingOneOfTwoScreensDoesNotLock() {
        val first = screen()
        val second = screen()
        AppLockGuard.onActivityStarted(first)
        AppLockGuard.onActivityStarted(second)
        second.finish()

        AppLockGuard.onActivityStopped(second)

        assertFalse(first.appLockManager.isLocked())
        assertTrue(HiddenChatsSession.isUnlocked())
    }

    @Test
    fun closingTheSecondScreenAfterTheFirstWasStoppedLocks() {
        val first = screen()
        val second = screen()
        AppLockGuard.onActivityStarted(first)
        AppLockGuard.onActivityStarted(second)
        AppLockGuard.onActivityStopped(first)
        second.finish()

        AppLockGuard.onActivityStopped(second)

        assertTrue(second.appLockManager.isLocked())
    }

    @Test
    fun withoutAnAppLockBackingOutLocksNothing() {
        val activity = screen()
        activity.baseConfig.isAppPasswordProtectionOn = false
        AppLockGuard.onActivityStarted(activity)
        activity.finish()

        AppLockGuard.onActivityStopped(activity)

        assertFalse(activity.appLockManager.isLocked())
        assertTrue(HiddenChatsSession.isUnlocked())
    }

    @Test
    fun theInvisibleCopyCodeScreenClosingNeverLocks() {
        val intent = CopyCodeActivity.intent(ApplicationProvider.getApplicationContext(), "482913")
        val activity = Robolectric.buildActivity(CopyCodeActivity::class.java, intent).create().get()
        activity.baseConfig.isAppPasswordProtectionOn = true
        activity.appLockManager.unlock()
        HiddenChatsSession.unlock()
        AppLockGuard.onActivityStarted(activity)
        activity.finish()

        AppLockGuard.onActivityStopped(activity)

        assertFalse(activity.appLockManager.isLocked())
        assertTrue(HiddenChatsSession.isUnlocked())
    }

    @Test
    fun anExtraStopNeverMakesTheCounterNegative() {
        val activity = screen()
        AppLockGuard.onActivityStopped(activity) // a stop with no matching start
        AppLockGuard.onActivityStarted(activity)

        AppLockGuard.onActivityStopped(activity) // not finishing: still no lock
        assertFalse(activity.appLockManager.isLocked())

        // A later start, then back-out, still counts from zero and locks.
        AppLockGuard.onActivityStarted(activity)
        activity.finish()
        AppLockGuard.onActivityStopped(activity)
        assertTrue(activity.appLockManager.isLocked())
    }

    @Test
    fun leavingAndComingBackWithinTheTimeoutKeepsTheHiddenChatsOpen() {
        val activity = screen()
        activity.baseConfig.unlockTimeoutDurationMs = 60_000L
        AppLockGuard.onActivityStarted(activity)
        AppLockGuard.onActivityStopped(activity)

        AppLockGuard.onActivityStarted(activity) // moments later

        assertTrue(HiddenChatsSession.isUnlocked())
    }

    @Test
    fun anotherStartedScreenIsNotAReturnToTheApp() {
        val first = screen()
        val second = screen()
        first.baseConfig.unlockTimeoutDurationMs = 0L
        AppLockGuard.onActivityStarted(first)

        // The app was never left, so the second screen starting must not read as coming back after a timeout of 0.
        AppLockGuard.onActivityStarted(second)

        assertTrue(HiddenChatsSession.isUnlocked())
    }
}
