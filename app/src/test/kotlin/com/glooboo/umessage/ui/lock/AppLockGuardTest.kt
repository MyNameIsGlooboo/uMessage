package com.glooboo.umessage.ui.lock

import android.app.Activity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.AppLockGuard
import com.glooboo.umessage.activities.CopyCodeActivity
import org.fossify.commons.FossifyApp
import org.fossify.commons.extensions.appLockManager
import org.fossify.commons.extensions.baseConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.fossify.commons.helpers.AppLockManager
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The app with its lock screen, without the real App's start-up work (spam engine, background replays). */
class LockTestApp : FossifyApp() {
    override val isAppLockFeatureAvailable = true
    override val appUnlockActivity = LockActivity::class.java

    /** As in the real App (I44): with "only chosen parts" a plain screen needs no lock screen. */
    override fun screenNeedsAppLock(activity: Activity): Boolean = LockScope.lockApplies(activity)
}

/** Any screen that comes to the front while the app is locked shows the lock screen first. */
@RunWith(AndroidJUnit4::class)
@Config(application = LockTestApp::class)
class AppLockGuardTest {
    /**
     * The lock manager is a process-wide singleton holding the settings of the app it was made with; each Robolectric
     * test has a fresh app, so drop it before and after (a locked one must not leak into other test classes).
     */
    @Before
    @After
    fun freshLockManager() {
        AppLockManager::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }

    private fun screen(): Activity = Robolectric.buildActivity(Activity::class.java).create().start().get()

    @Test
    fun `a plain screen opened while locked shows the lock screen`() {
        val activity = screen()
        activity.baseConfig.isAppPasswordProtectionOn = true
        activity.appLockManager.lock()
        AppLockGuard.onActivityResumed(activity)
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(LockActivity::class.java.name, started?.component?.className)
    }

    @Test
    fun `an unlocked app opens straight away`() {
        val activity = screen()
        activity.baseConfig.isAppPasswordProtectionOn = true
        activity.appLockManager.unlock()
        AppLockGuard.onActivityResumed(activity)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `the invisible copy-code screen never asks for the PIN`() {
        val intent = CopyCodeActivity.intent(ApplicationProvider.getApplicationContext(), "482913")
        val activity = Robolectric.buildActivity(CopyCodeActivity::class.java, intent).create().get()
        activity.baseConfig.isAppPasswordProtectionOn = true
        activity.appLockManager.lock()
        AppLockGuard.onActivityResumed(activity)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `without the lock nothing is asked`() {
        val activity = screen()
        activity.baseConfig.isAppPasswordProtectionOn = false
        AppLockGuard.onActivityResumed(activity)
        assertNull(shadowOf(activity).nextStartedActivity)
    }
}
