package com.glooboo.umessage.helpers

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.edit
import com.glooboo.umessage.activities.MainActivity
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Small settings of the main screen's bottom bar and of forwarding, kept in their own preferences file (the shared
 * `Config` keeps its preferences private to its subclass). Read it with [get] or [navPrefs]; every value also comes as
 * a [Flow] so a screen follows a change made in another activity (Settings, the rules screen).
 *
 * - [showForwardedTab]: the user's own choice for the Forwarded tab. null = never chosen: the tab then shows as soon as
 *   a forward rule exists. true / false are explicit choices (the Settings switch, or "Hide" on the one-time message
 *   after the first rule), and false keeps it hidden.
 * - [forwardedTabHintShown]: the one-time "Forwarded tab added. Hide it?" message was shown on this install.
 * - [forwardPaused]: the global "pause all forwarding" switch; read by `ForwardDelivery` before anything is sent.
 * - [forwardedSeenAt]: failed forwards that happened after this time have not been looked at (dot on the tab).
 */
class NavPrefs private constructor(private val prefs: SharedPreferences) {

    var showForwardedTab: Boolean?
        get() = readShowForwardedTab()
        set(value) = prefs.edit {
            if (value == null) remove(KEY_SHOW_FORWARDED_TAB) else putBoolean(KEY_SHOW_FORWARDED_TAB, value)
        }

    var forwardedTabHintShown: Boolean
        get() = prefs.getBoolean(KEY_FORWARDED_TAB_HINT_SHOWN, false)
        set(value) = prefs.edit { putBoolean(KEY_FORWARDED_TAB_HINT_SHOWN, value) }

    var forwardPaused: Boolean
        get() = prefs.getBoolean(KEY_FORWARD_PAUSED, false)
        set(value) = prefs.edit { putBoolean(KEY_FORWARD_PAUSED, value) }

    var forwardedSeenAt: Long
        get() = prefs.getLong(KEY_FORWARDED_SEEN_AT, 0L)
        set(value) = prefs.edit { putLong(KEY_FORWARDED_SEEN_AT, value) }

    /** The one-line explanation on top of the Archived tab was dismissed. */
    var archivedIntroDismissed: Boolean
        get() = prefs.getBoolean(KEY_ARCHIVED_INTRO_DISMISSED, false)
        set(value) = prefs.edit { putBoolean(KEY_ARCHIVED_INTRO_DISMISSED, value) }

    val showForwardedTabFlow: Flow<Boolean?> get() = observe { readShowForwardedTab() }
    val forwardPausedFlow: Flow<Boolean> get() = observe { forwardPaused }
    val forwardedSeenAtFlow: Flow<Long> get() = observe { forwardedSeenAt }
    val archivedIntroDismissedFlow: Flow<Boolean> get() = observe { archivedIntroDismissed }

    private fun readShowForwardedTab(): Boolean? =
        if (prefs.contains(KEY_SHOW_FORWARDED_TAB)) prefs.getBoolean(KEY_SHOW_FORWARDED_TAB, false) else null

    private fun <T> observe(read: () -> T): Flow<T> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(read()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(read())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    companion object {
        private const val PREFS_NAME = "spamless_nav"
        private const val KEY_SHOW_FORWARDED_TAB = "show_forwarded_tab"
        private const val KEY_FORWARDED_TAB_HINT_SHOWN = "forwarded_tab_hint_shown"
        private const val KEY_FORWARD_PAUSED = "forward_paused"
        private const val KEY_FORWARDED_SEEN_AT = "forwarded_seen_at"
        private const val KEY_ARCHIVED_INTRO_DISMISSED = "archived_intro_dismissed"

        fun get(context: Context): NavPrefs =
            NavPrefs(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }
}

val Context.navPrefs: NavPrefs get() = NavPrefs.get(this)

/**
 * Opens the main screen on a given tab (for rows in Settings such as "Archived conversations" or "Forwarding history").
 * The tab is shown even when it is normally hidden, and goes away again once the user leaves it.
 */
object MainTabLinks {
    const val EXTRA_OPEN_TAB = "com.glooboo.spamless.extra.OPEN_TAB"
    const val TAB_ARCHIVED = "archived"
    const val TAB_FORWARDED = "forwarded"
    const val TAB_BLOCKED = "blocked"
    const val TAB_MESSAGES = "messages"

    fun intent(context: Context, tab: String): Intent = Intent(context, MainActivity::class.java)
        .putExtra(EXTRA_OPEN_TAB, tab)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
}
