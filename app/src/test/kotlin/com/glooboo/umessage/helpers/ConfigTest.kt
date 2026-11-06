package com.glooboo.umessage.helpers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Smoke test: Config reads and writes real SharedPreferences under Robolectric. */
@RunWith(AndroidJUnit4::class)
class ConfigTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun simChoiceRoundTrip() {
        val config = Config.newInstance(context)
        assertThat(config.getUseSIMIdAtNumberOrNull("5550100")).isNull()

        config.saveUseSIMIdAtNumber("5550100", 2)

        assertThat(Config.newInstance(context).getUseSIMIdAtNumberOrNull("5550100")).isEqualTo(2)
    }

    @Test
    fun defaultSimFollowsThePhoneUntilSet() {
        val config = Config.newInstance(context)
        assertThat(config.defaultSimId).isEqualTo(DEFAULT_SIM_PHONE)

        config.defaultSimId = DEFAULT_SIM_ASK

        assertThat(Config.newInstance(context).defaultSimId).isEqualTo(DEFAULT_SIM_ASK)
    }

    @Test
    fun lockScreenDefaultsToSenderOnly() {
        assertThat(Config.newInstance(context).lockScreenVisibilitySetting).isEqualTo(LOCK_SCREEN_SENDER)
    }

    @Test
    fun upgradedInstallWithTheOldPinnedDefaultMovesToSenderOnly() {
        // SB-16: older versions pinned "sender and message" for updated installs without the user choosing it.
        prefs().edit().putInt(LOCK_SCREEN_VISIBILITY, LOCK_SCREEN_SENDER_MESSAGE).commit()

        assertThat(Config.newInstance(context).lockScreenVisibilitySetting).isEqualTo(LOCK_SCREEN_SENDER)
    }

    @Test
    fun oldNothingChoiceIsKept() {
        prefs().edit().putInt(LOCK_SCREEN_VISIBILITY, LOCK_SCREEN_NOTHING).commit()

        assertThat(Config.newInstance(context).lockScreenVisibilitySetting).isEqualTo(LOCK_SCREEN_NOTHING)
    }

    @Test
    fun choiceMadeNowIsKept() {
        Config.newInstance(context).lockScreenVisibilitySetting = LOCK_SCREEN_SENDER_MESSAGE

        assertThat(Config.newInstance(context).lockScreenVisibilitySetting).isEqualTo(LOCK_SCREEN_SENDER_MESSAGE)
    }

    private fun prefs() = context.getSharedPreferences(org.fossify.commons.helpers.PREFS_KEY, Context.MODE_PRIVATE)
}
