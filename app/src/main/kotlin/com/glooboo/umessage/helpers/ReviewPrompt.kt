package com.glooboo.umessage.helpers

import android.app.Activity
import android.os.SystemClock
import com.glooboo.umessage.BuildConfig

/**
 * Google Play build only: may show Google's own rating card, never anything of ours. The foss build has a
 * [ReviewLauncher] that does nothing and does not even count launches. See [ReviewPromptPolicy] for the rule.
 */
object ReviewPrompt {
    /** Debug builds only: an intent extra (boolean) that skips the rule, for testing the card. */
    const val EXTRA_FORCE_DEBUG = "com.glooboo.umessage.debug.FORCE_REVIEW"

    @Volatile private var lastFailureAt = 0L
    private var askedThisProcess = false

    /** Remembers a failed send (uptime clock), so no card appears right after one. Safe from any thread. */
    fun noteSendFailure() {
        lastFailureAt = SystemClock.elapsedRealtime().coerceAtLeast(1L)
    }

    /** Counts this launch day. Call once when the conversation list opens. */
    fun noteLaunch(activity: Activity) {
        if (!ReviewLauncher.AVAILABLE) return
        runCatching { ReviewPromptPrefs.get(activity).noteLaunch(System.currentTimeMillis()) }
    }

    /**
     * Asks at most once per process, when the rule allows it. [onMessagesList] is true on the conversation list
     * with no onboarding, lock or permission screen, [busy] while a backup or import runs. [force] is honoured in
     * debug builds only. Never throws.
     */
    fun maybeAsk(activity: Activity, onMessagesList: Boolean, busy: Boolean, force: Boolean = false) {
        if (!ReviewLauncher.AVAILABLE || askedThisProcess) return
        runCatching {
            val prefs = ReviewPromptPrefs.get(activity)
            val now = System.currentTimeMillis()
            val failed = ReviewPromptPolicy.isRecentFailure(SystemClock.elapsedRealtime(), lastFailureAt)
            val allowed = (force && BuildConfig.DEBUG) || ReviewPromptPolicy.shouldAsk(
                now = now,
                firstInstall = activity.packageManager.getPackageInfo(activity.packageName, 0).firstInstallTime,
                launchDays = prefs.launchDays,
                promptsShown = prefs.promptsShown,
                lastPromptAt = prefs.lastPromptAt,
                onMessagesList = onMessagesList,
                busy = busy,
                recentSendFailure = failed,
            )
            if (!allowed) return
            askedThisProcess = true
            prefs.recordPrompt(now)
            ReviewLauncher.launch(activity)
        }
    }
}
