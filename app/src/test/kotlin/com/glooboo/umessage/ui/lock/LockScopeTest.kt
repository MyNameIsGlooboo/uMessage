package com.glooboo.umessage.ui.lock

import android.app.Activity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.AppLockGuard
import com.glooboo.umessage.helpers.HiddenChatsSession
import com.google.common.truth.Truth.assertThat
import org.fossify.commons.extensions.appLockManager
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.helpers.AppLockManager
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** I44: which screens the app lock covers for "Whole app" and "Only chosen parts". */
@RunWith(AndroidJUnit4::class)
@Config(application = LockTestApp::class)
class LockScopeTest {

    @Before
    @After
    fun freshState() {
        AppLockManager::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        HiddenChatsSession.reset()
    }

    private fun screen(): Activity = Robolectric.buildActivity(Activity::class.java).create().start().get()

    private fun lockedScreen(scope: LockScope): Activity {
        val activity = screen()
        LockScope.set(activity, scope)
        activity.baseConfig.isAppPasswordProtectionOn = true
        activity.appLockManager.lock()
        return activity
    }

    @Test
    fun wholeAppLocksEveryScreenAndChosenPartsOnlyTheBlockedOnes() {
        assertThat(LockScope.WholeApp.lockApplies(blockedScreen = false)).isTrue()
        assertThat(LockScope.WholeApp.lockApplies(blockedScreen = true)).isTrue()
        assertThat(LockScope.ChosenParts.lockApplies(blockedScreen = false)).isFalse()
        assertThat(LockScope.ChosenParts.lockApplies(blockedScreen = true)).isTrue()
    }

    @Test
    fun chosenPartsHidesTheWindowOnlyWhereSomethingLockedCanShow() {
        assertThat(LockScope.WholeApp.protectsWindow(blockedScreen = false, lockedOpen = false)).isTrue()
        assertThat(LockScope.ChosenParts.protectsWindow(blockedScreen = false, lockedOpen = false)).isFalse()
        assertThat(LockScope.ChosenParts.protectsWindow(blockedScreen = false, lockedOpen = true)).isTrue()
        assertThat(LockScope.ChosenParts.protectsWindow(blockedScreen = true, lockedOpen = false)).isTrue()
    }

    @Test
    fun theScopeIsRememberedAndWholeAppIsTheDefault() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        assertThat(LockScope.current(context)).isEqualTo(LockScope.WholeApp)

        LockScope.set(context, LockScope.ChosenParts)

        assertThat(LockScope.current(context)).isEqualTo(LockScope.ChosenParts)
        assertThat(LockScope.fromKey("nonsense")).isEqualTo(LockScope.WholeApp)
    }

    @Test
    fun aLockedAppWithWholeAppAsksForThePinOnAnyScreen() {
        val activity = lockedScreen(LockScope.WholeApp)

        AppLockGuard.onActivityResumed(activity)

        assertThat(shadowOf(activity).nextStartedActivity?.component?.className)
            .isEqualTo(LockActivity::class.java.name)
    }

    @Test
    fun aLockedAppWithChosenPartsOpensAPlainScreenWithoutThePin() {
        val activity = lockedScreen(LockScope.ChosenParts)

        AppLockGuard.onActivityResumed(activity)

        assertThat(shadowOf(activity).nextStartedActivity).isNull()
        assertThat(LockScope.isLockedNow(activity)).isFalse()
        assertThat(LockScope.blockedLocked(activity)).isTrue()
    }

    @Test
    fun theBlockedTabIsBehindTheLockOnlyWhileTheLockIsOnAndCoversChosenParts() {
        val activity = lockedScreen(LockScope.ChosenParts)
        assertThat(LockScope.blockedLocked(activity)).isTrue()

        LockScope.set(activity, LockScope.WholeApp)
        assertThat(LockScope.blockedLocked(activity)).isFalse()

        LockScope.set(activity, LockScope.ChosenParts)
        activity.baseConfig.isAppPasswordProtectionOn = false
        assertThat(LockScope.blockedLocked(activity)).isFalse()
    }

    @Test
    fun unlockingForTheBlockedTabUsesTheAppLocksOwnTimeout() {
        val activity = lockedScreen(LockScope.ChosenParts)
        assertThat(activity.appLockManager.isLocked()).isTrue()

        activity.appLockManager.unlock()

        assertThat(activity.appLockManager.isLocked()).isFalse()
        assertThat(LockScope.protectsWindow(activity)).isTrue()
    }
}
