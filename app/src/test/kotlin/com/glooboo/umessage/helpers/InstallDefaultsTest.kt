package com.glooboo.umessage.helpers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.ui.onboarding.OnboardingState
import com.google.common.truth.Truth.assertThat
import org.fossify.commons.helpers.PREFS_KEY
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** U07, U42: a new install starts with the recycle bin on and the summary off; an older install keeps what it had. */
@RunWith(AndroidJUnit4::class)
class InstallDefaultsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val configPrefs = context.getSharedPreferences("install_defaults_test", Context.MODE_PRIVATE)
    private val marker = context.getSharedPreferences("umessage_install", Context.MODE_PRIVATE)
    private val cleanup = CleanupPrefs.get(context)

    @Before
    fun freshInstall() {
        configPrefs.edit().clear().commit()
        marker.edit().clear().commit()
        context.getSharedPreferences("spamless_onboarding", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("umessage_cleanup", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun aNewInstallKeepsDeletedMessagesStartsWithoutTheSummaryAndAsksForDeliveryReports() {
        InstallDefaults.apply(context, configPrefs)

        assertThat(configPrefs.getBoolean(USE_RECYCLE_BIN, false)).isTrue()
        assertThat(cleanup.digestOn).isFalse()
        assertThat(cleanup.digestChosen).isTrue()
        assertThat(configPrefs.getBoolean(ENABLE_DELIVERY_REPORTS, false)).isTrue()
        assertThat(marker.getBoolean("defaults_applied", false)).isTrue()
    }

    @Test
    fun aNewInstallIsRecognisedEvenWhenTheThemeKeyIsAlreadyThere() {
        val appPrefs = context.getSharedPreferences(PREFS_KEY, Context.MODE_PRIVATE)
        appPrefs.edit().clear().putBoolean("is_using_system_theme", true).commit()

        val config = Config.newInstance(context)

        assertThat(config.useRecycleBin).isTrue()
        assertThat(config.enableDeliveryReports).isTrue()
        assertThat(cleanup.digestOn).isFalse()
    }

    @Test
    fun theLaterLaunchesOfANewInstallChangeNothing() {
        InstallDefaults.apply(context, configPrefs)
        configPrefs.edit().putBoolean(USE_RECYCLE_BIN, false).putBoolean(ENABLE_DELIVERY_REPORTS, false).commit()
        cleanup.digestOn = true

        InstallDefaults.apply(context, configPrefs)

        assertThat(configPrefs.getBoolean(USE_RECYCLE_BIN, true)).isFalse()
        assertThat(configPrefs.getBoolean(ENABLE_DELIVERY_REPORTS, true)).isFalse()
        assertThat(cleanup.digestOn).isTrue()
    }

    @Test
    fun aSecondConfigInitKeepsEverything() {
        context.getSharedPreferences(PREFS_KEY, Context.MODE_PRIVATE).edit().clear().commit()
        Config.newInstance(context)
        OnboardingState.setCompleted(context)

        Config.newInstance(context)

        assertThat(Config.newInstance(context).useRecycleBin).isTrue()
        assertThat(Config.newInstance(context).enableDeliveryReports).isTrue()
        assertThat(context.cleanupPrefs.digestOn).isFalse()
    }

    @Test
    fun anOlderInstallKeepsTheBinOffAndTheSummaryOn() {
        OnboardingState.setCompleted(context)

        InstallDefaults.apply(context, configPrefs)

        assertThat(configPrefs.contains(USE_RECYCLE_BIN)).isFalse()
        assertThat(cleanup.digestOn).isTrue()
        assertThat(marker.getBoolean("defaults_applied", false)).isTrue()
    }

    @Test
    fun anOlderInstallThatSwitchedTheSummaryOffKeepsItOff() {
        OnboardingState.setCompleted(context)
        cleanup.digestOn = false

        InstallDefaults.apply(context, configPrefs)

        assertThat(cleanup.digestOn).isFalse()
    }

    @Test
    fun anOlderInstallThatNeverStoredDeliveryReportsKeepsThemOff() {
        OnboardingState.setCompleted(context)

        InstallDefaults.apply(context, configPrefs)

        assertThat(configPrefs.getBoolean(ENABLE_DELIVERY_REPORTS, true)).isFalse()
    }

    @Test
    fun anOlderInstallKeepsAStoredDeliveryReportsChoice() {
        OnboardingState.setCompleted(context)
        listOf(true, false).forEach { stored ->
            marker.edit().clear().commit()
            configPrefs.edit().putBoolean(ENABLE_DELIVERY_REPORTS, stored).commit()

            InstallDefaults.apply(context, configPrefs)

            assertThat(configPrefs.getBoolean(ENABLE_DELIVERY_REPORTS, !stored)).isEqualTo(stored)
        }
    }

    @Test
    fun aNewInstallNeverOverwritesStoredValues() {
        configPrefs.edit().putBoolean(USE_RECYCLE_BIN, false).putBoolean(ENABLE_DELIVERY_REPORTS, false).commit()
        cleanup.digestOn = true

        InstallDefaults.apply(context, configPrefs)

        assertThat(configPrefs.getBoolean(USE_RECYCLE_BIN, true)).isFalse()
        assertThat(configPrefs.getBoolean(ENABLE_DELIVERY_REPORTS, true)).isFalse()
        assertThat(cleanup.digestOn).isTrue()
    }

    @Test
    fun withTheMarkerPresentNothingIsTouched() {
        marker.edit().putBoolean("defaults_applied", true).commit()

        InstallDefaults.apply(context, configPrefs)

        assertThat(configPrefs.contains(USE_RECYCLE_BIN)).isFalse()
        assertThat(configPrefs.contains(ENABLE_DELIVERY_REPORTS)).isFalse()
        assertThat(cleanup.digestChosen).isFalse()
    }
}
