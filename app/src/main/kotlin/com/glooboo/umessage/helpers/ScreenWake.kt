package com.glooboo.umessage.helpers

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.os.PowerManager
import android.util.Log

/** What [ScreenWake] looks at for one new message. */
data class WakeState(
    val enabled: Boolean,
    /** A muted chat or a silent unknown sender: its notification is quiet, so it does not wake the screen. */
    val quiet: Boolean,
    val hidden: Boolean,
    /** The notification only updates one that is already showing. */
    val alertOnlyOnce: Boolean,
    val ringerNormal: Boolean,
    val dndOff: Boolean,
    val screenOn: Boolean,
)

/**
 * I50: turns the screen on for a few seconds after a new message, when the user chose that. Never for a quiet
 * notification, a hidden chat, a silent or Do Not Disturb phone, or a screen that is on already.
 */
object ScreenWake {
    private const val TAG = "ScreenWake"
    private const val WAKE_MS = 5_000L

    fun shouldWake(state: WakeState): Boolean = state.enabled && !state.quiet && !state.hidden &&
        !state.alertOnlyOnce && state.ringerNormal && state.dndOff && !state.screenOn

    @Suppress("DEPRECATION")
    fun maybeWake(context: Context, quiet: Boolean, alertOnlyOnce: Boolean) {
        if (!context.notifyPrefs.wakeScreen) return
        try {
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val filter = notifications.currentInterruptionFilter
            val state = WakeState(
                enabled = true,
                quiet = quiet,
                hidden = false,
                alertOnlyOnce = alertOnlyOnce,
                ringerNormal = audio.ringerMode == AudioManager.RINGER_MODE_NORMAL,
                dndOff = filter == NotificationManager.INTERRUPTION_FILTER_ALL ||
                    filter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN,
                screenOn = power.isInteractive,
            )
            if (!shouldWake(state)) return
            val flags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP
            power.newWakeLock(flags, "${context.packageName}:wake").acquire(WAKE_MS)
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not wake the screen", e)
        }
    }
}
