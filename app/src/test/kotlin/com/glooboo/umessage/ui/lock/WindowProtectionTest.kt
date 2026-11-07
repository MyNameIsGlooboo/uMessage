package com.glooboo.umessage.ui.lock

import android.app.Activity
import android.os.Build
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.AppLockGuard
import org.fossify.commons.extensions.baseConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class WindowProtectionTest {
    @Test
    fun `nothing is hidden without the lock`() {
        assertEquals(WindowProtection.NONE, WindowProtection.of(lockOn = false, blockScreenshots = true, sdk = 34))
    }

    @Test
    fun `with the lock Recents is hidden on 13+, and screenshots are blocked unless allowed`() {
        assertEquals(WindowProtection(secure = true, hideFromRecents = true), WindowProtection.of(true, true, 34))
        assertEquals(WindowProtection(secure = false, hideFromRecents = true), WindowProtection.of(true, false, 34))
        // Below 13 only FLAG_SECURE hides the Recents thumbnail.
        assertEquals(WindowProtection(secure = true, hideFromRecents = false), WindowProtection.of(true, true, 31))
    }

    @Test
    fun `screenshots are allowed by default, also on installs that never touched the setting`() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        activity.baseConfig.isAppPasswordProtectionOn = true
        assertFalse(activity.baseConfig.appLockBlockScreenshots)
        AppLockGuard.onActivityCreated(activity, null)
        assertFalse(activity.isSecure())
        assertEquals(Build.VERSION_CODES.UPSIDE_DOWN_CAKE, Build.VERSION.SDK_INT)
        // Recents stays hidden on Android 13+ with the default.
        assertEquals(WindowProtection(secure = false, hideFromRecents = true), WindowProtection.of(true, false, 34))
    }

    @Test
    fun `below Android 13 the default blanks Recents only while the screen is left`() {
        assertEquals(
            WindowProtection(secure = false, hideFromRecents = false, secureWhileLeaving = true),
            WindowProtection.of(true, false, 32),
        )
        assertEquals(WindowProtection(secure = true, hideFromRecents = false), WindowProtection.of(true, true, 32))
        assertFalse(WindowProtection.of(false, false, 32).secureWhileLeaving)
        assertFalse(WindowProtection.of(true, false, 34).secureWhileLeaving)
    }

    @Test
    @Config(sdk = [31])
    fun `pausing sets the secure flag on old Android and resuming clears it`() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        activity.baseConfig.isAppPasswordProtectionOn = true
        AppLockGuard.onActivityCreated(activity, null)
        assertFalse(activity.isSecure())
        AppLockGuard.onActivityPaused(activity)
        assertTrue(activity.isSecure())
        AppLockGuard.applyWindowProtection(activity)
        assertFalse(activity.isSecure())
    }

    @Test
    fun `pausing sets no flag on Android 13+ where Recents has its own switch`() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        activity.baseConfig.isAppPasswordProtectionOn = true
        AppLockGuard.onActivityPaused(activity)
        assertFalse(activity.isSecure())
    }

    @Test
    fun `turning Block screenshots on sets the flag`() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        activity.baseConfig.isAppPasswordProtectionOn = true
        activity.baseConfig.appLockBlockScreenshots = true
        AppLockGuard.onActivityCreated(activity, null)
        assertTrue(activity.isSecure())
    }

    @Test
    fun `allowing screenshots or turning the lock off clears the flag at the next resume`() {
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        activity.baseConfig.isAppPasswordProtectionOn = true
        activity.baseConfig.appLockBlockScreenshots = true
        AppLockGuard.applyWindowProtection(activity)
        assertTrue(activity.isSecure())
        activity.baseConfig.appLockBlockScreenshots = false
        AppLockGuard.applyWindowProtection(activity)
        assertFalse(activity.isSecure())
        activity.baseConfig.appLockBlockScreenshots = true
        activity.baseConfig.isAppPasswordProtectionOn = false
        AppLockGuard.applyWindowProtection(activity)
        assertFalse(activity.isSecure())
    }

    private fun Activity.isSecure() = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
}
