package com.glooboo.umessage.ui.settings

import android.os.Build
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.glooboo.umessage.R

/**
 * The app's own language (B16 / ID-35, N02): the phone's language or one of the languages the app ships. Stored as
 * Android's per-app language, so it also shows in the system's app language settings (the locales come from the
 * generated localeConfig, one per values-xx folder). Each name is written in its own language and script;
 * [englishName] only orders the list. [tag] is a BCP-47 tag; Indonesian is "id" here and lives in the values-in
 * folder, Simplified Chinese is "zh-CN" and lives in values-zh-rCN, Traditional Chinese is "zh-TW" and lives in
 * values-zh-rTW.
 */
enum class AppLanguage(val tag: String, @StringRes val label: Int, val englishName: String) {
    SYSTEM("", R.string.settings_f_language_system, "System default"),
    ENGLISH("en", R.string.settings_f_language_en, "English"),
    PERSIAN("fa", R.string.settings_f_language_fa, "Persian"),
    ARABIC("ar", R.string.settings_f_language_ar, "Arabic"),
    TURKISH("tr", R.string.settings_f_language_tr, "Turkish"),
    SPANISH("es", R.string.settings_f_language_es, "Spanish"),
    RUSSIAN("ru", R.string.settings_f_language_ru, "Russian"),
    HINDI("hi", R.string.settings_f_language_hi, "Hindi"),
    INDONESIAN("id", R.string.settings_f_language_id, "Indonesian"),
    PORTUGUESE_BRAZIL("pt-BR", R.string.settings_f_language_pt_br, "Portuguese (Brazil)"),
    FRENCH("fr", R.string.settings_f_language_fr, "French"),
    GERMAN("de", R.string.settings_f_language_de, "German"),
    ITALIAN("it", R.string.settings_f_language_it, "Italian"),
    CHINESE_SIMPLIFIED("zh-CN", R.string.settings_f_language_zh_cn, "Chinese (Simplified)"),
    CHINESE_TRADITIONAL("zh-TW", R.string.settings_f_language_zh_tw, "Chinese (Traditional)"),
    JAPANESE("ja", R.string.settings_f_language_ja, "Japanese"),
    KOREAN("ko", R.string.settings_f_language_ko, "Korean"),
    VIETNAMESE("vi", R.string.settings_f_language_vi, "Vietnamese"),
    THAI("th", R.string.settings_f_language_th, "Thai"),
    POLISH("pl", R.string.settings_f_language_pl, "Polish"),
    UKRAINIAN("uk", R.string.settings_f_language_uk, "Ukrainian"),
    BENGALI("bn", R.string.settings_f_language_bn, "Bengali"),
    URDU("ur", R.string.settings_f_language_ur, "Urdu"),
    ;

    companion object {
        private val TRADITIONAL_CHINESE_TAGS = setOf("zh-tw", "zh-hk", "zh-mo")

        /**
         * The list the first screen and Settings both show: "System default" first, then English, then every other
         * language sorted by its English name.
         */
        val choices: List<AppLanguage> =
            listOf(SYSTEM, ENGLISH) +
                entries.filter { it != SYSTEM && it != ENGLISH }.sortedBy { it.englishName }

        /**
         * Android 13+ applies a per-app language to every screen. Below that AppCompat would only switch its own
         * activities and leave the others in the phone's language, so the picker is not offered there and the app
         * follows the phone's language.
         */
        val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        /** The choice for stored language [tags] ("fa", "en-US,fa", "pt-BR", "" for none). */
        fun fromTags(tags: String): AppLanguage {
            val first = tags.substringBefore(',').trim().replace('_', '-').lowercase()
            val language = first.substringBefore('-').let { if (it == "in") "id" else it }
            val named = choices.filter { it != SYSTEM }
            if (first.startsWith("zh-hant") || first in TRADITIONAL_CHINESE_TAGS) return CHINESE_TRADITIONAL
            return named.firstOrNull { it.tag.lowercase() == first }
                ?: named.firstOrNull { it.tag.substringBefore('-').lowercase() == language }
                ?: SYSTEM
        }

        fun current(): AppLanguage = fromTags(AppCompatDelegate.getApplicationLocales().toLanguageTags())

        /** Switches the app to [language]; Android recreates the open screens in it. */
        fun apply(language: AppLanguage) {
            val locales = if (language == SYSTEM) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(language.tag)
            }
            AppCompatDelegate.setApplicationLocales(locales)
        }
    }
}
