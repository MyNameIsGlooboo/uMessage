package com.glooboo.umessage.helpers

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * U07, U42: the defaults that are not the same for a new install and an old one. A new install keeps deleted
 * messages in the recycle bin for 30 days, starts with the weekly summary off and asks for delivery reports; an
 * install made before that keeps what it had (bin off unless switched on, summary on unless switched off, delivery
 * reports off unless switched on). Run when [Config] is created.
 */
object InstallDefaults {
    fun apply(context: Context, configPrefs: SharedPreferences, firstRun: Boolean) {
        val cleanup = CleanupPrefs.get(context)
        if (firstRun) {
            if (!configPrefs.contains(USE_RECYCLE_BIN)) configPrefs.edit { putBoolean(USE_RECYCLE_BIN, true) }
            if (!cleanup.digestChosen) cleanup.digestOn = false
            // Stored, so the next launch (no longer a first run) finds the key and leaves it alone.
            if (!configPrefs.contains(ENABLE_DELIVERY_REPORTS)) {
                configPrefs.edit { putBoolean(ENABLE_DELIVERY_REPORTS, true) }
            }
        } else {
            if (!cleanup.digestChosen) cleanup.digestOn = true
            // The default became true; an older install that never stored the value keeps its old "off".
            if (!configPrefs.contains(ENABLE_DELIVERY_REPORTS)) {
                configPrefs.edit { putBoolean(ENABLE_DELIVERY_REPORTS, false) }
            }
        }
    }
}
