package com.glooboo.umessage.helpers

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** How dates are written in lists and thread separators: the Gregorian calendar, the Jalali one, or both. */
enum class DateStyle(val key: String) {
    GREGORIAN("gregorian"),
    JALALI("jalali"),
    BOTH("both"),
    ;

    companion object {
        /**
         * The style for a [stored] value. Nothing stored yet: the older "Jalali dates" switch decides, so people who
         * turned it on keep their Jalali dates ([legacyJalali]).
         */
        fun resolve(stored: String?, legacyJalali: Boolean): DateStyle =
            values().firstOrNull { it.key == stored } ?: if (legacyJalali) JALALI else GREGORIAN
    }
}

/** App text size: a multiplier on the font scale the system already applies (Settings > Appearance > Text size). */
enum class TextSize(val key: String, val scale: Float) {
    XSMALL("xsmall", 0.8f),
    SMALL("small", 0.9f),
    DEFAULT("default", 1f),
    LARGE("large", 1.15f),
    LARGEST("largest", 1.3f),
    HUGE("huge", 1.45f),
    GIANT("giant", 1.6f),
    ;

    companion object {
        fun fromKey(key: String?): TextSize = values().firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * The typeface family of all app text (Settings > Appearance > Font). [SYSTEM] hands every script, Persian and Arabic
 * included, to the phone's own sans-serif; it also covers a "Google style" choice, which on Android 10+ would differ
 * only in the Latin letters.
 */
enum class AppFont(val key: String) {
    UMESSAGE("umessage"),
    SYSTEM("system"),
    ;

    companion object {
        val DEFAULT = UMESSAGE

        fun fromKey(key: String?): AppFont = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

private const val MIN_FONT_SCALE = 0.5f
private const val MAX_FONT_SCALE = 3f
private const val MAX_APP_FONT_SCALE = 2f

/**
 * The font scale to draw with: the system's [systemFontScale] times the app's [size]. The app's share stops at
 * [MAX_APP_FONT_SCALE] so the biggest levels keep the layouts usable; a system scale above that is always kept.
 */
fun appFontScale(systemFontScale: Float, size: TextSize): Float {
    val upper = maxOf(systemFontScale, MAX_APP_FONT_SCALE).coerceAtMost(MAX_FONT_SCALE)
    return (systemFontScale * size.scale).coerceIn(MIN_FONT_SCALE, upper)
}

/**
 * Wave 3 display settings and one-time flags, in their own preferences file (`Config` keeps its preferences private).
 * [dateStyle] extends the thread screen's older Jalali switch; [textSize] scales all text; the rest remember what the
 * first-day checklist and the first-block receipt have already shown.
 */
class W3cPrefs private constructor(
    private val prefs: SharedPreferences,
    private val legacyJalali: () -> Boolean,
) {

    var dateStyle: DateStyle
        get() = DateStyle.resolve(prefs.getString(KEY_DATE_STYLE, null), legacyJalali())
        set(value) = prefs.edit { putString(KEY_DATE_STYLE, value.key) }

    var textSize: TextSize
        get() = TextSize.fromKey(prefs.getString(KEY_TEXT_SIZE, null))
        set(value) = prefs.edit { putString(KEY_TEXT_SIZE, value.key) }

    var appFont: AppFont
        get() = AppFont.fromKey(prefs.getString(KEY_APP_FONT, null))
        set(value) = prefs.edit { putString(KEY_APP_FONT, value.key) }

    /** I37: a pinch on the conversation changes [textSize] (on unless turned off). */
    var pinchZoomText: Boolean
        get() = prefs.getBoolean(KEY_PINCH_ZOOM, true)
        set(value) = prefs.edit { putBoolean(KEY_PINCH_ZOOM, value) }

    /** I30: iPhone "Liked ..." texts show as a reaction on the message they name (on unless turned off). */
    var showTapbacks: Boolean
        get() = prefs.getBoolean(KEY_SHOW_TAPBACKS, true)
        set(value) = prefs.edit { putBoolean(KEY_SHOW_TAPBACKS, value) }

    /** The user closed the first-day checklist card. */
    var checklistDismissed: Boolean
        get() = prefs.getBoolean(KEY_CHECKLIST_DISMISSED, false)
        set(value) = prefs.edit { putBoolean(KEY_CHECKLIST_DISMISSED, value) }

    /** The Blocked tab was opened at least once (a step of the checklist). */
    var blockedTabVisited: Boolean
        get() = prefs.getBoolean(KEY_BLOCKED_VISITED, false)
        set(value) = prefs.edit { putBoolean(KEY_BLOCKED_VISITED, value) }

    /** The one-time note about the first held message was answered. */
    var firstBlockReceiptShown: Boolean
        get() = prefs.getBoolean(KEY_FIRST_BLOCK_SHOWN, false)
        set(value) = prefs.edit { putBoolean(KEY_FIRST_BLOCK_SHOWN, value) }

    /** I36: unread conversations come first in the inbox, after the pinned ones. */
    var unreadFirst: Boolean
        get() = prefs.getBoolean(KEY_UNREAD_FIRST, false)
        set(value) = prefs.edit { putBoolean(KEY_UNREAD_FIRST, value) }

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    companion object {
        private const val PREFS_NAME = "spamless_w3c"
        private const val KEY_DATE_STYLE = "date_style"
        private const val KEY_TEXT_SIZE = "text_size"
        private const val KEY_APP_FONT = "app_font"
        private const val KEY_PINCH_ZOOM = "pinch_zoom_text"
        private const val KEY_SHOW_TAPBACKS = "show_tapbacks"
        private const val KEY_CHECKLIST_DISMISSED = "checklist_dismissed"
        private const val KEY_BLOCKED_VISITED = "blocked_tab_visited"
        private const val KEY_FIRST_BLOCK_SHOWN = "first_block_receipt_shown"
        private const val KEY_UNREAD_FIRST = "unread_first"

        fun get(context: Context): W3cPrefs {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return W3cPrefs(prefs) { ThreadPrefs.get(app).useJalaliDates }
        }
    }
}

val Context.w3cPrefs: W3cPrefs get() = W3cPrefs.get(this)
