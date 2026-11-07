package com.glooboo.umessage.helpers

import android.content.Context
import android.provider.Settings

/**
 * Android's own lock-screen notification setting ("Notifications on lock screen", "Sensitive notifications"). When it
 * is set to show all content, Android draws a notification's private version in full on the locked screen, whatever
 * the app asked for, so uMessage's "sender only" cannot hide the text. Used to tell the user so (F10).
 */
object LockScreenSystemSetting {
    private const val KEY = "lock_screen_allow_private_notifications"

    /** True when the value of the system setting [raw] (null when the phone has none) shows all content. */
    fun showsAllContent(raw: Int?): Boolean = raw == 1

    /** True only when the phone clearly says it shows private notification content on the lock screen. */
    fun systemShowsPrivateContent(context: Context): Boolean = runCatching {
        showsAllContent(Settings.Secure.getInt(context.contentResolver, KEY, -1).takeIf { it != -1 })
    }.getOrDefault(false)
}
