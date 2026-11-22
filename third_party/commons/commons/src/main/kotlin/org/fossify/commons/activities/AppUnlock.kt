package org.fossify.commons.activities

import android.app.Activity
import android.content.Intent
import android.view.View
import org.fossify.commons.FossifyApp
import org.fossify.commons.extensions.appLockManager

/**
 * Shows the app's lock screen ([FossifyApp.appUnlockActivity]) while the app is locked (uMessage patch: the commons
 * `AppLockActivity` is gone, the app provides its own). Without one configured it throws rather than leave the
 * screen readable. The app can exempt screens ([FossifyApp.screenNeedsAppLock]).
 *
 * uMessage (M-SEC1): while the lock screen is asked for, this screen's content is INVISIBLE, so not a frame of it is
 * drawn (or can be screenshotted) before the lock screen covers it; the next check after unlocking shows it again.
 */
fun Activity.maybeLaunchAppUnlockActivity(requestCode: Int) {
    if (!appLockManager.isLocked() || (application as? FossifyApp)?.screenNeedsAppLock(this) == false) {
        showAppContent(true)
        return
    }
    val target = (application as? FossifyApp)?.appUnlockActivity
    if (target?.isInstance(this) == true) return
    showAppContent(false)
    checkNotNull(target) { "The app is locked but FossifyApp.appUnlockActivity is not set" }
    Intent(this, target).apply {
        addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        startActivityForResult(this, requestCode)
    }
}

/** uMessage (M-SEC1): shows or hides the screen's own content; the themed window background stays. */
private fun Activity.showAppContent(show: Boolean) {
    val visibility = if (show) View.VISIBLE else View.INVISIBLE
    findViewById<View>(android.R.id.content)?.let { if (it.visibility != visibility) it.visibility = visibility }
}
