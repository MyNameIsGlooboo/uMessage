package com.glooboo.umessage.ui.theme

import android.content.Context
import androidx.compose.ui.text.font.FontFamily
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.AppFont
import com.glooboo.umessage.helpers.W3cPrefs
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppFontTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun defaultIsTheUMessageFont() {
        assertThat(AppFont.DEFAULT).isEqualTo(AppFont.UMESSAGE)
        assertThat(W3cPrefs.get(context).appFont).isEqualTo(AppFont.UMESSAGE)
    }

    @Test fun keysAreStableAndUnknownOnesFallBack() {
        assertThat(AppFont.entries.map { it.key }).containsExactly("umessage", "system").inOrder()
        AppFont.entries.forEach { assertThat(AppFont.fromKey(it.key)).isEqualTo(it) }
        assertThat(AppFont.fromKey("comic")).isEqualTo(AppFont.UMESSAGE)
        assertThat(AppFont.fromKey(null)).isEqualTo(AppFont.UMESSAGE)
    }

    @Test fun preferenceRoundTrips() {
        val prefs = W3cPrefs.get(context)
        prefs.appFont = AppFont.SYSTEM
        assertThat(W3cPrefs.get(context).appFont).isEqualTo(AppFont.SYSTEM)
        prefs.appFont = AppFont.UMESSAGE
        assertThat(W3cPrefs.get(context).appFont).isEqualTo(AppFont.UMESSAGE)
    }

    @Test fun uMessageFontKeepsTheBundledFamilies() {
        val type = typographyFor(AppFont.UMESSAGE)
        assertThat(type).isSameInstanceAs(UMessageTypography)
        assertThat(type.titleLarge.fontFamily).isEqualTo(GoogleSansFamily)
        assertThat(type.bodyMedium.fontFamily).isEqualTo(RobotoFamily)
    }

    @Test fun systemFontUsesTheSystemFamilyAndKeepsTheScale() {
        val system = typographyFor(AppFont.SYSTEM)
        assertThat(typographyFor(AppFont.SYSTEM)).isSameInstanceAs(system)
        listOf(system.displayLarge, system.titleMedium, system.bodyLarge, system.labelSmall).forEach {
            assertThat(it.fontFamily).isEqualTo(FontFamily.Default)
        }
        assertThat(system.bodyMedium.fontSize).isEqualTo(UMessageTypography.bodyMedium.fontSize)
        assertThat(system.titleMedium.fontWeight).isEqualTo(UMessageTypography.titleMedium.fontWeight)
        assertThat(system.bodyLarge.lineHeight).isEqualTo(UMessageTypography.bodyLarge.lineHeight)
    }
}
