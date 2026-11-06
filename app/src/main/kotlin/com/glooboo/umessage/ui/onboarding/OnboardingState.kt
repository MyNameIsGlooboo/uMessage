package com.glooboo.umessage.ui.onboarding

import android.content.Context
import androidx.core.content.edit

/** The steps of the first-run flow, in order. */
enum class OnboardingPage { WELCOME, ALLOW, MODE, KEEP_RUNNING }

/** Persists whether the first-run onboarding was finished. */
object OnboardingState {
    private const val PREFS = "spamless_onboarding"
    private const val KEY_COMPLETED = "completed"
    private const val KEY_OEM_STEP_PREFIX = "oem_step_done_"

    fun isCompleted(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_COMPLETED, false)

    fun setCompleted(context: Context, completed: Boolean = true) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_COMPLETED, completed) }
    }

    /** Whether the user marked a "Keep uMessage running" step whose state Android cannot report as done. */
    fun isOemStepMarked(context: Context, stepKey: String): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_OEM_STEP_PREFIX + stepKey, false)

    fun setOemStepMarked(context: Context, stepKey: String, marked: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit { putBoolean(KEY_OEM_STEP_PREFIX + stepKey, marked) }
    }

    /** The Allow step is left out when uMessage is already the default app and every permission is granted. */
    fun pagesFor(allowNeeded: Boolean): List<OnboardingPage> =
        OnboardingPage.entries.filter { allowNeeded || it != OnboardingPage.ALLOW }
}
