package com.glooboo.umessage

import android.app.Activity
import android.app.Application
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.view.WindowManager
import org.fossify.commons.FossifyApp
import org.fossify.commons.activities.BaseComposeActivity
import org.fossify.commons.activities.BaseSimpleActivity
import org.fossify.commons.activities.maybeLaunchAppUnlockActivity
import org.fossify.commons.extensions.appLockManager
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.REQUEST_APP_UNLOCK
import org.fossify.commons.helpers.ensureBackgroundThread
import com.glooboo.umessage.activities.CopyCodeActivity
import com.glooboo.umessage.activities.SplashActivity
import com.glooboo.umessage.extensions.rescheduleAllScheduledMessages
import com.glooboo.umessage.helpers.DailyChecks
import com.glooboo.umessage.helpers.HiddenChatsSession
import com.glooboo.umessage.helpers.MessagingCache
import com.glooboo.umessage.messaging.failStaleOutboxMessages
import com.glooboo.umessage.helpers.DebugStrictMode
import com.glooboo.umessage.messaging.rearmReminders
import com.glooboo.umessage.messaging.tidyScheduledAttachments
import com.glooboo.umessage.spam.data.PendingSmsQueue
import com.glooboo.umessage.spam.engine.SpamEngine
import com.glooboo.umessage.ui.lock.LockActivity
import com.glooboo.umessage.ui.lock.LockScope
import com.glooboo.umessage.ui.lock.WindowProtection
import com.glooboo.umessage.ui.settings.AppLocale
import com.glooboo.umessage.ui.theme.ThemePresets

class App : FossifyApp() {
    override val isAppLockFeatureAvailable = true

    /** The commons base activities start this Compose lock screen instead of the old commons one. */
    override val appUnlockActivity = LockActivity::class.java

    /** I44: with "only chosen parts" only the Blocked screens ask for the lock; everything else opens at once. */
    override fun screenNeedsAppLock(activity: Activity): Boolean = LockScope.lockApplies(activity)

    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLocale.wrap(base))

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(AppLocale.RecreateOnChange)
        SpamEngine.install()
        DebugStrictMode.install()
        ThemePresets.migrateIfNeeded(this)
        registerActivityLifecycleCallbacks(AppLockGuard)
        registerContactsObserver()

        ensureBackgroundThread {
            PendingSmsQueue.replay(this)
            tidyScheduledAttachments()
            rescheduleAllScheduledMessages()
            rearmReminders()
            DailyChecks.arm(this)
            DailyChecks.runIfDue(this)
            failStaleOutboxMessages()
            SpamEngine.purgeExpired(this)
        }
    }

    @Volatile
    private var contactsObserverRegistered = false

    /**
     * PF-B8: listens for contact edits so the name and photo caches do not go stale. Called at start and again once
     * the Contacts permission is granted; does nothing without the permission or when already registered.
     */
    fun registerContactsObserver() {
        if (contactsObserverRegistered || !hasPermission(PERMISSION_READ_CONTACTS)) return
        contactsObserverRegistered = true
        listOf(
            ContactsContract.Contacts.CONTENT_URI,
            ContactsContract.Data.CONTENT_URI,
            ContactsContract.DisplayPhoto.CONTENT_URI
        ).forEach {
            try {
                contentResolver.registerContentObserver(it, true, contactsObserver)
            } catch (_: Exception) {
            }
        }
    }

    private val contactsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            MessagingCache.namePhoto.evictAll()
            MessagingCache.participantsCache.evictAll()
        }
    }
}

/**
 * The app lock for every screen, in one place, so a screen cannot forget it:
 * - shows the lock screen when a screen comes to the front while the app is locked (the commons base activities did
 *   this themselves; plain `ComponentActivity` screens did not, which let them be opened from Recents unlocked);
 * - keeps screen content out of the Recents thumbnail, and out of screenshots unless the user allowed them, while the
 *   lock is on ([WindowProtection]). It is applied in onActivityCreated, which runs inside the activity's own
 *   onCreate, before its window is first shown, and again on every resume so a changed setting applies at once;
 * - locks again as soon as the user leaves the app with back, as the main screen used to do on its own.
 */
internal object AppLockGuard : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = applyWindowProtection(activity)

    override fun onActivityResumed(activity: Activity) {
        applyWindowProtection(activity)
        // The invisible "Copy code" screen (N6) shows nothing of the app, so it never asks for the PIN.
        if (activity is LockActivity || activity is SplashActivity || activity is CopyCodeActivity) return
        // These bases already ask for the lock screen in their own onResume.
        if (activity is BaseSimpleActivity || activity is BaseComposeActivity) return
        activity.maybeLaunchAppUnlockActivity(REQUEST_APP_UNLOCK)
    }

    private var started = 0

    override fun onActivityStarted(activity: Activity) {
        // B06: the hidden chats stay unlocked only for the lock timeout after the app was left.
        if (started == 0) {
            HiddenChatsSession.appReturned(System.currentTimeMillis(), activity.baseConfig.unlockTimeoutDurationMs)
        }
        started++
    }

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0) HiddenChatsSession.appLeft(System.currentTimeMillis())
        if (activity is LockActivity || activity is SplashActivity || activity is CopyCodeActivity) return
        // The last visible screen was closed (back out of the app), not just hidden behind another app.
        if (started == 0 && activity.isFinishing && activity.baseConfig.isAppPasswordProtectionOn) {
            activity.appLockManager.lock()
            HiddenChatsSession.lock()
        }
    }

    /** Applies the current lock settings to [activity]'s window; call after changing them on an open screen. */
    fun applyWindowProtection(activity: Activity) {
        val config = activity.baseConfig
        val protection = WindowProtection.of(LockScope.protectsWindow(activity), config.appLockBlockScreenshots)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.setRecentsScreenshotEnabled(!protection.hideFromRecents)
        }
        if (protection.secure) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    /**
     * Android 8 to 12 cannot hide the Recents thumbnail any other way: with the lock on and screenshots allowed the
     * window is made secure as soon as it is paused, so the thumbnail is blank. The next resume clears it again.
     */
    override fun onActivityPaused(activity: Activity) {
        val blockScreenshots = activity.baseConfig.appLockBlockScreenshots
        val protection = WindowProtection.of(LockScope.protectsWindow(activity), blockScreenshots)
        if (protection.secureWhileLeaving) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
