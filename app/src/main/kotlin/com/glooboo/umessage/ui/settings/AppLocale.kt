package com.glooboo.umessage.ui.settings

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.LocaleList
import androidx.core.content.edit
import java.util.Locale
import java.util.WeakHashMap

/**
 * I1: the in-app language on Android 8 to 12. Android 13+ has a per-app language of its own (see [AppLanguage]);
 * below that the chosen language tag is kept in the app's own preferences and every screen puts it on its context in
 * `attachBaseContext` through [wrap]. A screen that was opened before the choice changed is recreated when it comes
 * back to the front ([RecreateOnChange]).
 */
object AppLocale {
    private const val PREFS = "umessage_app_locale"
    private const val KEY_TAG = "tag"

    /** The stored language tag ("fa", "pt-BR", "" for the phone's language). Only used below Android 13. */
    fun storedTag(context: Context): String = prefs(context).getString(KEY_TAG, "").orEmpty()

    fun store(context: Context, tag: String) {
        prefs(context).edit { putString(KEY_TAG, tag) }
    }

    // The application context is not available yet while the Application itself is being attached.
    private fun prefs(context: Context) =
        (context.applicationContext ?: context).getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The locale for stored [tag], or null for the phone's language. */
    fun localeFor(tag: String): Locale? = tag.takeIf { it.isNotBlank() }?.let { Locale.forLanguageTag(it) }

    /**
     * [base] with the chosen language applied. A no-op on Android 13+ (the system applies the per-app language) and
     * when no language was chosen. Call it from `attachBaseContext`.
     */
    fun wrap(base: Context, supported: Boolean = AppLanguage.supported): Context {
        if (supported) return base
        val locale = localeFor(storedTag(base)) ?: return base
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }

    /** Remembers each screen's language when it was created and recreates it when the choice has changed since. */
    object RecreateOnChange : Application.ActivityLifecycleCallbacks {
        private val created = WeakHashMap<Activity, String>()

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            created[activity] = storedTag(activity)
        }

        override fun onActivityResumed(activity: Activity) {
            if (AppLanguage.supported) return
            val tagAtCreate = created[activity] ?: return
            if (tagAtCreate != storedTag(activity)) {
                created[activity] = storedTag(activity)
                activity.recreate()
            }
        }

        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) {
            created.remove(activity)
        }
    }
}
