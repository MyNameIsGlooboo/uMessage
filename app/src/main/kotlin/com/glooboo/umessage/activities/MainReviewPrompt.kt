package com.glooboo.umessage.activities

import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.glooboo.umessage.BuildConfig
import com.glooboo.umessage.helpers.BackupJob
import com.glooboo.umessage.helpers.ReviewPrompt
import com.glooboo.umessage.ui.onboarding.OnboardingState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val REVIEW_DELAY_MS = 4_000L

/**
 * Called from the conversation list's onResume. Google Play build only (the foss build does nothing). Waits a few
 * seconds, so a card never covers a screen the user is just arriving at, and skips it when the list is no longer
 * resumed. [ReviewPrompt] applies the rule. [onList] says that no permission or default-app screen is showing.
 * Returns the job, so the caller can pass it back next time and a second resume replaces the first wait.
 */
internal fun ComponentActivity.scheduleReviewPrompt(previous: Job?, onList: () -> Boolean): Job? {
    previous?.cancel()
    if (isFinishing || !OnboardingState.isCompleted(this)) return null
    ReviewPrompt.noteLaunch(this)
    return lifecycleScope.launch {
        delay(REVIEW_DELAY_MS)
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@launch
        val force = BuildConfig.DEBUG && intent?.getBooleanExtra(ReviewPrompt.EXTRA_FORCE_DEBUG, false) == true
        val busy = BackupJob.state.value is BackupJob.State.Running
        ReviewPrompt.maybeAsk(this@scheduleReviewPrompt, onMessagesList = onList(), busy = busy, force = force)
    }
}
