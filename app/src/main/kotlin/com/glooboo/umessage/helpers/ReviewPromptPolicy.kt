package com.glooboo.umessage.helpers

/**
 * When the Google Play build may ask for a rating. Pure rule, no Android: every condition must hold, so one failing
 * condition is enough to keep quiet. [lastPromptAt] 0 means never asked; [firstInstall] 0 means unknown.
 */
object ReviewPromptPolicy {
    const val DAY_MS = 24L * 60 * 60 * 1000
    const val MIN_INSTALL_DAYS = 7
    const val MIN_LAUNCH_DAYS = 5
    const val MAX_PROMPTS = 2
    const val MIN_DAYS_BETWEEN_PROMPTS = 120
    const val FAILURE_QUIET_MS = 10L * 60 * 1000

    @Suppress("LongParameterList")
    fun shouldAsk(
        now: Long,
        firstInstall: Long,
        launchDays: Int,
        promptsShown: Int,
        lastPromptAt: Long,
        onMessagesList: Boolean,
        busy: Boolean,
        recentSendFailure: Boolean,
    ): Boolean {
        if (!onMessagesList || busy || recentSendFailure) return false
        return installedLongEnough(now, firstInstall) &&
            launchDays >= MIN_LAUNCH_DAYS &&
            promptsAllowed(now, promptsShown, lastPromptAt)
    }

    /** True when a send failed less than [FAILURE_QUIET_MS] before [now]. [failedAt] 0 means none. */
    fun isRecentFailure(now: Long, failedAt: Long): Boolean =
        failedAt > 0L && now - failedAt in 0 until FAILURE_QUIET_MS

    private fun installedLongEnough(now: Long, firstInstall: Long): Boolean =
        firstInstall > 0L && now - firstInstall >= MIN_INSTALL_DAYS * DAY_MS

    private fun promptsAllowed(now: Long, promptsShown: Int, lastPromptAt: Long): Boolean = when {
        promptsShown >= MAX_PROMPTS -> false
        promptsShown <= 0 && lastPromptAt <= 0L -> true
        else -> now - lastPromptAt >= MIN_DAYS_BETWEEN_PROMPTS * DAY_MS
    }
}
