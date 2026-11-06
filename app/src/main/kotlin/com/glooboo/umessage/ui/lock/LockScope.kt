package com.glooboo.umessage.ui.lock

import android.app.Activity
import android.content.Context
import androidx.core.content.edit
import com.glooboo.umessage.helpers.HiddenChatsSession
import com.glooboo.umessage.ui.log.DecisionLogActivity
import com.glooboo.umessage.ui.spam.BlockedMessagesActivity
import com.glooboo.umessage.ui.spam.BlockedThreadActivity
import org.fossify.commons.extensions.appLockManager
import org.fossify.commons.extensions.baseConfig

/**
 * I44: what the app lock covers once it is on.
 *
 * With [ChosenParts] the app opens without the lock screen; only the Blocked tab and its screens ask for it (and the
 * hidden chats, which have their own unlock, see `HiddenChats`). The lock state and its timeout are the app lock's own,
 * so unlocking the Blocked tab keeps it open for the same time as the whole-app lock would.
 */
enum class LockScope(val key: String) {
    /** Every screen, as every version so far did. */
    WholeApp("whole"),

    /** Only the Blocked tab and screens, plus the hidden chats. */
    ChosenParts("chosen"),
    ;

    /** True when the lock screen shows first on a screen: any screen for [WholeApp], else only a Blocked one. */
    fun lockApplies(blockedScreen: Boolean): Boolean = this == WholeApp || blockedScreen

    /**
     * True when a screen hides from Recents (and from screenshots, if the user chose that). [WholeApp] hides every
     * screen. [ChosenParts] hides a Blocked screen, and any screen while something locked is open ([lockedOpen]);
     * what shows with everything locked is the plain inbox, which is not secret.
     */
    fun protectsWindow(blockedScreen: Boolean, lockedOpen: Boolean): Boolean =
        this == WholeApp || blockedScreen || lockedOpen

    companion object {
        private const val PREFS_NAME = "spamless_lock_scope"
        private const val KEY_SCOPE = "scope"

        fun fromKey(key: String?): LockScope = entries.firstOrNull { it.key == key } ?: WholeApp

        fun current(context: Context): LockScope = fromKey(prefs(context).getString(KEY_SCOPE, null))

        fun set(context: Context, scope: LockScope) = prefs(context).edit { putString(KEY_SCOPE, scope.key) }

        private fun prefs(context: Context) =
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        /** The screens that show blocked messages. */
        fun isBlockedScreen(activity: Activity): Boolean =
            activity is BlockedMessagesActivity || activity is BlockedThreadActivity || activity is DecisionLogActivity

        /** True when the app lock is on and covers only chosen parts: the Blocked tab is behind it, the rest is not. */
        fun blockedLocked(context: Context): Boolean =
            context.baseConfig.isAppPasswordProtectionOn && current(context) == ChosenParts

        /** True when [activity] must show the lock screen while the app is locked. */
        fun lockApplies(activity: Activity): Boolean = current(activity).lockApplies(isBlockedScreen(activity))

        /** True when [activity] is behind the lock right now: the app is locked and the lock covers this screen. */
        fun isLockedNow(activity: Activity): Boolean =
            activity.baseConfig.isAppPasswordProtectionOn && lockApplies(activity) && activity.appLockManager.isLocked()

        /** True when [activity]'s window hides from Recents and screenshots (the app lock is on). */
        fun protectsWindow(activity: Activity): Boolean {
            if (!activity.baseConfig.isAppPasswordProtectionOn) return false
            val scope = current(activity)
            if (scope == WholeApp) return true
            val lockedOpen = !activity.appLockManager.isLocked() || HiddenChatsSession.isUnlocked()
            return scope.protectsWindow(isBlockedScreen(activity), lockedOpen)
        }
    }
}
