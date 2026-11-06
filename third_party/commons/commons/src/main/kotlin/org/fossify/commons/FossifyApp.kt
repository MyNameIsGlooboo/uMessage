package org.fossify.commons

import android.app.Activity
import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import org.fossify.commons.extensions.appLockManager
import org.fossify.commons.extensions.checkUseEnglish

open class FossifyApp : Application() {

    open val isAppLockFeatureAvailable = false

    /**
     * The screen that asks for the app lock credential (uMessage patch: the app provides its own lock screen instead
     * of the old commons `AppLockActivity`). Required when [isAppLockFeatureAvailable] is true. It is started by
     * [org.fossify.commons.activities.maybeLaunchAppUnlockActivity], must unlock [appLockManager] and finish with
     * `RESULT_OK` on success.
     */
    open val appUnlockActivity: Class<out Activity>? = null

    /**
     * uMessage patch: whether [activity] shows the lock screen while the app is locked. The default is every screen;
     * an app that locks only some of its screens (uMessage's "only chosen parts") answers false for the others.
     */
    open fun screenNeedsAppLock(activity: Activity): Boolean = true

    override fun onCreate() {
        super.onCreate()
        check(!isAppLockFeatureAvailable || appUnlockActivity != null) { "isAppLockFeatureAvailable needs an appUnlockActivity" }
        checkUseEnglish()
        setupAppLockManager()
    }

    private fun setupAppLockManager() {
        if (isAppLockFeatureAvailable) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(appLockManager)
        }
    }
}
