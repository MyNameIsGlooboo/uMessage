package com.glooboo.umessage.ui.settings

import android.app.Activity
import android.content.Context
import android.util.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import java.util.Locale

/** I1: the in-app language on Android 8 to 12 (the system's per-app language only exists from Android 13). */
@RunWith(AndroidJUnit4::class)
class AppLocaleTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val systemLocale = Locale.getDefault()

    @After
    fun restore() {
        Locale.setDefault(systemLocale)
        AppLocale.store(context, "")
    }

    @Test
    fun `no stored language leaves the context alone`() {
        AppLocale.store(context, "")
        assertThat(AppLocale.wrap(context, supported = false)).isSameInstanceAs(context)
    }

    @Test
    fun `a stored language is put on the wrapped context, with its direction`() {
        AppLocale.store(context, "fa")
        val wrapped = AppLocale.wrap(context, supported = false)
        assertThat(wrapped.resources.configuration.locales[0].language).isEqualTo("fa")
        assertThat(wrapped.resources.configuration.layoutDirection).isEqualTo(LayoutDirection.RTL)
        assertThat(wrapped.getString(com.glooboo.umessage.R.string.onb_allow_optional)).isEqualTo("اختیاری")
    }

    @Test
    fun `Android 13 and later is left to the system`() {
        AppLocale.store(context, "fa")
        assertThat(AppLocale.wrap(context, supported = true)).isSameInstanceAs(context)
    }

    @Test
    fun `tags map to locales and the phone language is null`() {
        assertThat(AppLocale.localeFor("")).isNull()
        assertThat(AppLocale.localeFor("pt-BR")).isEqualTo(Locale.forLanguageTag("pt-BR"))
    }

    @Test
    @Config(sdk = [31])
    fun `choosing a language below Android 13 stores it and the picker reads it back`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        assertThat(AppLanguage.supported).isFalse()
        assertThat(AppLanguage.current(activity)).isEqualTo(AppLanguage.SYSTEM)
        AppLanguage.apply(activity, AppLanguage.PERSIAN)
        assertThat(AppLocale.storedTag(activity)).isEqualTo("fa")
        assertThat(AppLanguage.current(activity)).isEqualTo(AppLanguage.PERSIAN)
        AppLanguage.apply(activity, AppLanguage.SYSTEM)
        assertThat(AppLanguage.current(activity)).isEqualTo(AppLanguage.SYSTEM)
    }
}
