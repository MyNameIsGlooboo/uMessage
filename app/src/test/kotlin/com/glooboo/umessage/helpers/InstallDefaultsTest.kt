package com.glooboo.umessage.helpers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** U07, U42: a new install starts with the recycle bin on and the summary off; an older install keeps what it had. */
@RunWith(AndroidJUnit4::class)
class InstallDefaultsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val configPrefs = context.getSharedPreferences("install_defaults_test", Context.MODE_PRIVATE)
    private val cleanup = CleanupPrefs.get(context)

    @Test
    fun aNewInstallKeepsDeletedMessagesAndStartsWithoutTheSummary() {
        InstallDefaults.apply(context, configPrefs, firstRun = true)

        assertThat(configPrefs.getBoolean(USE_RECYCLE_BIN, false)).isTrue()
        assertThat(cleanup.digestOn).isFalse()
        assertThat(cleanup.digestChosen).isTrue()
    }

    @Test
    fun theLaterLaunchesOfANewInstallChangeNothing() {
        InstallDefaults.apply(context, configPrefs, firstRun = true)
        configPrefs.edit().putBoolean(USE_RECYCLE_BIN, false).apply()

        InstallDefaults.apply(context, configPrefs, firstRun = false)

        assertThat(configPrefs.getBoolean(USE_RECYCLE_BIN, true)).isFalse()
        assertThat(cleanup.digestOn).isFalse()
    }

    @Test
    fun anOlderInstallKeepsTheBinOffAndTheSummaryOn() {
        InstallDefaults.apply(context, configPrefs, firstRun = false)

        assertThat(configPrefs.contains(USE_RECYCLE_BIN)).isFalse()
        assertThat(cleanup.digestOn).isTrue()
    }

    @Test
    fun anOlderInstallThatSwitchedTheSummaryOffKeepsItOff() {
        cleanup.digestOn = false

        InstallDefaults.apply(context, configPrefs, firstRun = false)

        assertThat(cleanup.digestOn).isFalse()
    }

    @Test
    fun aNewInstallStoresDeliveryReportsOn() {
        InstallDefaults.apply(context, configPrefs, firstRun = true)

        assertThat(configPrefs.getBoolean(ENABLE_DELIVERY_REPORTS, false)).isTrue()
    }

    @Test
    fun theSecondLaunchOfANewInstallKeepsDeliveryReportsOn() {
        InstallDefaults.apply(context, configPrefs, firstRun = true)
        InstallDefaults.apply(context, configPrefs, firstRun = false)

        assertThat(configPrefs.getBoolean(ENABLE_DELIVERY_REPORTS, false)).isTrue()
    }

    @Test
    fun anOlderInstallThatNeverStoredDeliveryReportsKeepsThemOff() {
        InstallDefaults.apply(context, configPrefs, firstRun = false)

        assertThat(configPrefs.getBoolean(ENABLE_DELIVERY_REPORTS, true)).isFalse()
    }

    @Test
    fun anOlderInstallKeepsAStoredDeliveryReportsChoice() {
        listOf(true, false).forEach { stored ->
            configPrefs.edit().putBoolean(ENABLE_DELIVERY_REPORTS, stored).apply()

            InstallDefaults.apply(context, configPrefs, firstRun = false)

            assertThat(configPrefs.getBoolean(ENABLE_DELIVERY_REPORTS, !stored)).isEqualTo(stored)
        }
    }
}
