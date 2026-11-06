package com.glooboo.umessage.ui.settings.search

import android.content.Context
import android.content.res.Resources
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.glooboo.umessage.ui.settings.search.SettingsIndexEntry.Needs
import java.util.Locale
import org.junit.Test
import org.junit.runner.RunWith

/** Every entry of [SettingsIndex] has all its strings, in English and Persian, and real queries find the right page. */
@RunWith(AndroidJUnit4::class)
class SettingsIndexTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val english = resourcesFor("en")
    private val persian = resourcesFor("fa")
    private val all = setOf(Needs.DUAL_SIM, Needs.DECISION_LOG, Needs.LANGUAGE_CHOICE)

    private fun resourcesFor(tag: String): Resources {
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return context.createConfigurationContext(config).resources
    }

    @Test
    fun everyEntryStringExistsAndIsNotBlank() {
        listOf(english, persian).forEach { resources ->
            SettingsIndex.entries.forEach { entry ->
                assertThat(resources.getString(entry.titleRes)).isNotEmpty()
                assertThat(resources.getString(entry.pageRes)).isNotEmpty()
                entry.summaryRes?.let { assertThat(resources.getString(it)).isNotEmpty() }
                val keywords = resources.getString(checkNotNull(entry.keywordsRes) { "entry without keywords" })
                assertThat(keywords.trim()).isNotEmpty()
            }
        }
    }

    @Test
    fun everyEntryHasKeywords() {
        assertThat(SettingsIndex.entries.filter { it.keywordsRes == null }).isEmpty()
    }

    @Test
    fun theUiStringsExistInBothLanguages() {
        listOf(
            com.glooboo.umessage.R.string.settings_search_open,
            com.glooboo.umessage.R.string.settings_search_hint,
            com.glooboo.umessage.R.string.settings_search_none,
            com.glooboo.umessage.R.string.settings_search_start,
        ).forEach { id ->
            assertThat(english.getString(id)).isNotEmpty()
            assertThat(persian.getString(id)).isNotEmpty()
        }
    }

    @Test
    fun theIndexCoversEveryPageOfSettings() {
        val targets = SettingsIndex.entries.map { it.target }.toSet()
        assertThat(targets).containsAtLeast(
            SettingsTarget.SPAM, SettingsTarget.NOTIFICATIONS, SettingsTarget.TYPING, SettingsTarget.STORAGE,
            SettingsTarget.APPEARANCE, SettingsTarget.BACKUP, SettingsTarget.APP_LOCK, SettingsTarget.ABOUT,
            SettingsTarget.FORWARD_RULES, SettingsTarget.FORWARD_OPTIONS, SettingsTarget.SPAM_SETTINGS,
        )
    }

    @Test
    fun englishQueriesFindTheRightPage() {
        val search = SettingsSearch(english, all)
        assertThat(search.find("dark").first().entry.target).isEqualTo(SettingsTarget.APPEARANCE)
        assertThat(search.find("font").first().entry.target).isEqualTo(SettingsTarget.APPEARANCE)
        assertThat(search.find("backup").first().entry.target).isEqualTo(SettingsTarget.BACKUP)
        assertThat(search.find("spam").first().entry.target).isEqualTo(SettingsTarget.SPAM)
        assertThat(search.find("fingerprint").first().entry.target).isEqualTo(SettingsTarget.APP_LOCK)
        assertThat(search.find("pdf").first().entry.target).isEqualTo(SettingsTarget.BACKUP)
        assertThat(search.find("ringtone").map { it.entry.target }).contains(SettingsTarget.NOTIFICATIONS)
        assertThat(search.find("recycle").first().entry.target).isEqualTo(SettingsTarget.STORAGE)
        assertThat(search.find("lnaguage").first().entry.target).isEqualTo(SettingsTarget.LANGUAGE)
    }

    @Test
    fun persianQueriesFindTheRightPage() {
        val search = SettingsSearch(persian, all)
        assertThat(search.find("پشتیبان").first().entry.target).isEqualTo(SettingsTarget.BACKUP)
        assertThat(search.find("تاریک").first().entry.target).isEqualTo(SettingsTarget.APPEARANCE)
        assertThat(search.find("اثر انگشت").first().entry.target).isEqualTo(SettingsTarget.APP_LOCK)
        assertThat(search.find("هرزنامه")).isNotEmpty()
        // Arabic letter forms typed on an Arabic keyboard
        assertThat(search.find("پشتيبان").first().entry.target).isEqualTo(SettingsTarget.BACKUP)
    }

    @Test
    fun showsAtMostEightResultsAndNoneForNonsense() {
        val search = SettingsSearch(english, all)
        assertThat(search.find("message").size).isAtMost(SettingsSearchMatcher.MAX_RESULTS)
        assertThat(search.find("zzqx")).isEmpty()
    }

    @Test
    fun rowsThatAreNotOnThisPhoneAreHidden() {
        val plain = SettingsSearch(english)
        val targets = plain.find("sim").map { it.entry.target }
        assertThat(targets).doesNotContain(SettingsTarget.DEFAULT_SIM)
        assertThat(plain.find("recent activity")).isEmpty()
        assertThat(SettingsSearch(english, all).find("sim").map { it.entry.target })
            .contains(SettingsTarget.DEFAULT_SIM)
    }
}
