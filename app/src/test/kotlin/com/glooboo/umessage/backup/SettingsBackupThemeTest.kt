package com.glooboo.umessage.backup

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.ui.theme.ThemeChoice
import com.glooboo.umessage.ui.theme.ThemeColours
import com.glooboo.umessage.ui.theme.ThemeMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsBackupThemeTest {
    @Test fun readsModeAndColours() {
        assertThat(SettingsBackup.themeChoice("DARK/INK")).isEqualTo(ThemeChoice(ThemeMode.DARK, ThemeColours.INK))
    }

    @Test fun mapsRemovedColoursKeepingTheMode() {
        assertThat(SettingsBackup.themeChoice("DARK/CORAL"))
            .isEqualTo(ThemeChoice(ThemeMode.DARK, ThemeColours.POMEGRANATE))
        assertThat(SettingsBackup.themeChoice("LIGHT/SUNSET"))
            .isEqualTo(ThemeChoice(ThemeMode.LIGHT, ThemeColours.POMEGRANATE))
        assertThat(SettingsBackup.themeChoice("BLACK/FOREST"))
            .isEqualTo(ThemeChoice(ThemeMode.BLACK, ThemeColours.HARBOR))
        assertThat(SettingsBackup.themeChoice("FOREST")).isEqualTo(ThemeChoice(ThemeMode.DARK, ThemeColours.HARBOR))
    }

    @Test fun readsAnOlderSingleLook() {
        assertThat(SettingsBackup.themeChoice("SKY")).isEqualTo(ThemeChoice(ThemeMode.SYSTEM, ThemeColours.SKY))
    }

    @Test fun ignoresUnknownNames() {
        assertThat(SettingsBackup.themeChoice("DARK/MARTIAN")).isNull()
        assertThat(SettingsBackup.themeChoice("x")).isNull()
        assertThat(SettingsBackup.themeChoice(null)).isNull()
    }
}
