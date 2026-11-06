package com.glooboo.umessage.helpers

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.glooboo.umessage.ui.onboarding.OnboardingState

/**
 * U07, U42: the defaults that are not the same for a new install and an old one. A new install keeps deleted
 * messages in the recycle bin for 30 days, starts with the weekly summary off and asks for delivery reports; an
 * install made before that keeps what it had (bin off unless switched on, summary on unless switched off, delivery
 * reports off unless switched on). Run when [Config] is created.
 *
 * A fresh install is told from an older one by the welcome flow never having been finished, not by a theme key that
 * other code may write first. The answer is applied once: a marker in its own preferences file records it.
 */
object InstallDefaults {
    private const val MARKER_PREFS = "umessage_install"
    private const val KEY_DEFAULTS_APPLIED = "defaults_applied"
    private val lock = Any()

    /**
     * Applies the defaults once. Does nothing when the marker is already there. [newInstall] is true when the welcome
     * flow was never finished. The values are written before the marker, so a stop in between only repeats the same
     * writes, which never overwrite a stored value.
     */
    fun apply(
        context: Context,
        configPrefs: SharedPreferences,
        newInstall: Boolean = !OnboardingState.isCompleted(context),
    ) {
        synchronized(lock) {
            val marker = context.applicationContext.getSharedPreferences(MARKER_PREFS, Context.MODE_PRIVATE)
            if (marker.getBoolean(KEY_DEFAULTS_APPLIED, false)) return
            val cleanup = CleanupPrefs.get(context)
            if (newInstall) applyNewInstall(configPrefs, cleanup) else applyOlderInstall(configPrefs, cleanup)
            marker.edit(commit = true) { putBoolean(KEY_DEFAULTS_APPLIED, true) }
        }
    }

    private fun applyNewInstall(configPrefs: SharedPreferences, cleanup: CleanupPrefs) {
        configPrefs.edit(commit = true) {
            if (!configPrefs.contains(USE_RECYCLE_BIN)) putBoolean(USE_RECYCLE_BIN, true)
            if (!configPrefs.contains(ENABLE_DELIVERY_REPORTS)) putBoolean(ENABLE_DELIVERY_REPORTS, true)
        }
        if (!cleanup.digestChosen) cleanup.digestOn = false
    }

    private fun applyOlderInstall(configPrefs: SharedPreferences, cleanup: CleanupPrefs) {
        // The default became true; an older install that never stored the value keeps its old "off".
        if (!configPrefs.contains(ENABLE_DELIVERY_REPORTS)) {
            configPrefs.edit(commit = true) { putBoolean(ENABLE_DELIVERY_REPORTS, false) }
        }
        if (!cleanup.digestChosen) cleanup.digestOn = true
    }
}
