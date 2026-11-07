package com.glooboo.umessage.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.ui.onboarding.WelcomePage
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * N02: the language list is data, every entry is named in its own script, the list is sorted, and both screens show
 * all of it.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class LanguageListTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val named = AppLanguage.choices.filter { it != AppLanguage.SYSTEM }

    @Test
    fun systemDefaultIsFirstThenEnglishThenTheRestByEnglishName() {
        assertThat(AppLanguage.choices.first()).isEqualTo(AppLanguage.SYSTEM)
        assertThat(AppLanguage.SYSTEM.tag).isEmpty()
        assertThat(AppLanguage.choices[1]).isEqualTo(AppLanguage.ENGLISH)
        val rest = AppLanguage.choices.drop(2)
        assertThat(rest.map { it.englishName }).isInOrder()
        assertThat(rest.map { it.tag })
            .containsExactly(
                "ar", "bn", "zh-CN", "fr", "de", "hi", "id", "it", "ja", "ko", "fa", "pl", "pt-BR",
                "ru", "es", "th", "tr", "uk", "vi",
            )
            .inOrder()
    }

    @Test
    fun everyLanguageHasADisplayNameAndAUniqueTag() {
        val names = AppLanguage.choices.map { context.getString(it.label) }
        assertThat(names.all { it.isNotBlank() }).isTrue()
        assertThat(names.toSet()).hasSize(AppLanguage.choices.size)
        assertThat(AppLanguage.choices.map { it.tag }.toSet()).hasSize(AppLanguage.choices.size)
        assertThat(named.all { Locale.forLanguageTag(it.tag).language.isNotEmpty() }).isTrue()
    }

    @Test
    fun namesAreWrittenInTheirOwnLanguage() {
        val expected = mapOf(
            AppLanguage.ENGLISH to "English",
            AppLanguage.PERSIAN to "فارسی",
            AppLanguage.ARABIC to "العربية",
            AppLanguage.TURKISH to "Türkçe",
            AppLanguage.SPANISH to "Español",
            AppLanguage.RUSSIAN to "Русский",
            AppLanguage.HINDI to "हिन्दी",
            AppLanguage.INDONESIAN to "Bahasa Indonesia",
            AppLanguage.PORTUGUESE_BRAZIL to "Português (Brasil)",
            AppLanguage.FRENCH to "Français",
            AppLanguage.GERMAN to "Deutsch",
            AppLanguage.ITALIAN to "Italiano",
            AppLanguage.CHINESE_SIMPLIFIED to "简体中文",
            AppLanguage.JAPANESE to "日本語",
            AppLanguage.KOREAN to "한국어",
            AppLanguage.VIETNAMESE to "Tiếng Việt",
            AppLanguage.THAI to "ไทย",
            AppLanguage.POLISH to "Polski",
            AppLanguage.UKRAINIAN to "Українська",
            AppLanguage.BENGALI to "বাংলা",
        )
        expected.forEach { (language, name) -> assertThat(context.getString(language.label)).isEqualTo(name) }
    }

    @Test
    fun storedTagsMapBackToTheirChoice() {
        named.forEach { assertThat(AppLanguage.fromTags(it.tag)).isEqualTo(it) }
        assertThat(AppLanguage.fromTags("in")).isEqualTo(AppLanguage.INDONESIAN)
        assertThat(AppLanguage.fromTags("pt-PT")).isEqualTo(AppLanguage.PORTUGUESE_BRAZIL)
        assertThat(AppLanguage.fromTags("ar-EG,en")).isEqualTo(AppLanguage.ARABIC)
        assertThat(AppLanguage.fromTags("")).isEqualTo(AppLanguage.SYSTEM)
        assertThat(AppLanguage.fromTags("sv")).isEqualTo(AppLanguage.SYSTEM)
        assertThat(AppLanguage.fromTags("de-AT")).isEqualTo(AppLanguage.GERMAN)
        assertThat(AppLanguage.fromTags("zh-Hans-CN")).isEqualTo(AppLanguage.CHINESE_SIMPLIFIED)
    }

    @Test
    fun theSettingsDialogListsEveryLanguage() {
        compose.setContent {
            UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
                LanguageChoiceDialog(AppLanguage.choices, AppLanguage.ENGLISH, onSelect = {}, onDismiss = {})
            }
        }
        AppLanguage.choices.forEach { compose.onNodeWithText(context.getString(it.label)).assertExists() }
    }

    @Test
    @Config(qualifiers = "w360dp-h360dp")
    fun theDialogScrollsToTheLastLanguageOnASmallScreen() {
        compose.setContent {
            UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
                LanguageChoiceDialog(AppLanguage.choices, AppLanguage.ENGLISH, onSelect = {}, onDismiss = {})
            }
        }
        compose.onNodeWithText(context.getString(AppLanguage.choices.last().label)).performScrollTo().assertExists()
    }

    @Test
    fun theFirstScreenListsTheSameLanguages() {
        compose.setContent {
            UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
                WelcomePage(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    reduceMotion = true,
                    onLanguage = {},
                )
            }
        }
        compose.onNodeWithContentDescription("Language, English").assertIsDisplayed().performClick()
        // The chip also says "English", so a name can appear twice.
        named.forEach { compose.onAllNodesWithText(context.getString(it.label)).onFirst().assertExists() }
        compose.onNodeWithText("System default").assertDoesNotExist()
    }
}
