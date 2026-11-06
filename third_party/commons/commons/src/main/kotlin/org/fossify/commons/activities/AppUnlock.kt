package org.fossify.commons.activities

import android.app.Activity
import android.content.Intent
import org.fossify.commons.FossifyApp
import org.fossify.commons.extensions.appLockManager

/**
 * Shows the app's lock screen ([FossifyApp.appUnlockActivity]) while the app is locked (uMessage patch: the commons
 * `AppLockActivity` is gone, the app provides its own). Without one configured it throws rather than leave the
 * screen readable. The app can exempt screens ([FossifyApp.screenNeedsAppLock]).
 */
fun Activity.maybeLaunchAppUnlockActivity(requestCode: Int) {
    if (!appLockManager.isLocked()) return
    if ((application as? FossifyApp)?.screenNeedsAppLock(this) == false) return
    val target = (application as? FossifyApp)?.appUnlockActivity
        ?: error("The app is locked but FossifyApp.appUnlockActivity is not set")
    if (target.isInstance(this)) return
    Intent(this, target).apply {
        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        startActivityForResult(this, requestCode)
    }
}
