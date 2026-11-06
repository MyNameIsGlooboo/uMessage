package com.glooboo.umessage.helpers

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * U07, U42: the two defaults that are not the same for a new install and an old one. A new install keeps deleted
 * messages in the recycle bin for 30 days and starts with the weekly summary off; an install made before that keeps
 * what it had (bin off unless switched on, summary on unless switched off). Run when [Config] is created.
 */
object InstallDefaults {
    fun apply(context: Context, configPrefs: SharedPreferences, firstRun: Boolean) {
        val cleanup = CleanupPrefs.get(context)
        if (firstRun) {
            if (!configPrefs.contains(USE_RECYCLE_BIN)) configPrefs.edit { putBoolean(USE_RECYCLE_BIN, true) }
            if (!cleanup.digestChosen) cleanup.digestOn = false
        } else if (!cleanup.digestChosen) {
            cleanup.digestOn = true
        }
    }
}
