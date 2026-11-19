package com.glooboo.umessage.spam.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.ProviderTruthFlag
import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.UnknownSenderMode
import com.glooboo.umessage.testing.ProviderTruthFlagRule
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The settings of provider truth (7.1, S5): one filter switch, read from the old mode while its own key is missing,
 * the two group switches, and the one-time step that turns auto-delete off. Robolectric gives each test fresh prefs.
 */
@RunWith(AndroidJUnit4::class)
class SpamSwitchSettingsTest {

    @get:Rule val flag = ProviderTruthFlagRule(enabled = false)

    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The prefs as 0.4.14 left them. */
    private fun stored(mode: String?, purgeDays: Int? = null, notify: Boolean? = null) {
        context.getSharedPreferences("spamless_spam_settings", Context.MODE_PRIVATE).edit().apply {
            mode?.let { putString("mode", it) }
            purgeDays?.let { putInt("purge_after_days", it) }
            notify?.let { putBoolean("notify_on_block", it) }
        }.commit()
    }

    private fun store(legacySwitch: Boolean = false) = SpamSettingsStore(context) { legacySwitch }

    @Test fun theMappingTableOf71() {
        assertThat(SpamSettingsStore.filterOnFromMode(null, legacyBlockUnknown = false)).isTrue()
        assertThat(SpamSettingsStore.filterOnFromMode("SMART", legacyBlockUnknown = false)).isTrue()
        assertThat(SpamSettingsStore.filterOnFromMode("STRICT", legacyBlockUnknown = false)).isTrue()
        assertThat(SpamSettingsStore.filterOnFromMode("OFF", legacyBlockUnknown = false)).isFalse()
        assertThat(SpamSettingsStore.filterOnFromMode("OFF", legacyBlockUnknown = true)).isTrue()
        assertThat(SpamSettingsStore.filterOnFromMode("garbage", legacyBlockUnknown = false)).isTrue()
    }

    @Test fun aFreshInstallHasTheFilterAndBothGroupsOn() {
        val settings = store().get()
        assertThat(settings.filterOn).isTrue()
        assertThat(settings.codeGroupOn).isTrue()
        assertThat(settings.wroteGroupOn).isTrue()
    }

    /** S5: an Off user's first text after the update is read with the switch off, before any one-time write. */
    @Test fun theReaderMapsTheOldModeBeforeTheOneTimeWrite() {
        stored(mode = "OFF", notify = true)
        assertThat(store().get().filterOn).isFalse()
        assertThat(store(legacySwitch = true).get().filterOn).isTrue()
        assertThat(store().get().notifyOnBlock).isTrue()
    }

    @Test fun smartAndStrictUsersGetTheFilterOn() {
        stored(mode = "STRICT")
        assertThat(store().get().filterOn).isTrue()
    }

    @Test fun theOneTimeStepWritesTheSwitchAndKeepForeverOnce() {
        stored(mode = "OFF", purgeDays = 30)
        val store = store()
        assertThat(store.applyProviderTruthMapping()).isTrue()
        assertThat(store.get().filterOn).isFalse()
        assertThat(store.get().purgeAfterDays).isEqualTo(0)
        assertThat(store.get().mode).isEqualTo(UnknownSenderMode.OFF)

        // Later choices stay: the step runs once.
        store.update { it.copy(purgeAfterDays = 7, filterOn = true) }
        assertThat(store.applyProviderTruthMapping()).isFalse()
        assertThat(store.get().purgeAfterDays).isEqualTo(7)
        assertThat(store.get().filterOn).isTrue()
    }

    @Test fun withTheFlagOnAutoDeleteReadsKeepForeverUntilTheStepRan() {
        stored(mode = "SMART", purgeDays = 30)
        assertThat(store().get().purgeAfterDays).isEqualTo(30)
        flagOn {
            assertThat(store().get().purgeAfterDays).isEqualTo(0)
        }
    }

    /** The 0.4.14 screens change the mode; the switch follows. A new screen changes the switch; the mode follows. */
    @Test fun modeAndSwitchStayInStep() {
        val store = store()
        store.update { it.copy(mode = UnknownSenderMode.OFF) }
        assertThat(store.get().filterOn).isFalse()
        store.update { it.copy(filterOn = true) }
        assertThat(store.get().mode).isEqualTo(UnknownSenderMode.SMART)
        store.update { it.copy(filterOn = false) }
        assertThat(store.get().mode).isEqualTo(UnknownSenderMode.OFF)
        store.update { it.copy(mode = UnknownSenderMode.STRICT) }
        assertThat(store.get().filterOn).isTrue()
    }

    @Test fun keptInStepLeavesABothSidedChangeAlone() {
        val current = SpamSettings()
        val next = current.copy(mode = UnknownSenderMode.OFF, filterOn = true)
        assertThat(SpamSettingsStore.keptInStep(current, next)).isEqualTo(next)
    }

    @Test fun groupSwitchesAreStored() {
        val store = store()
        store.update { it.copy(codeGroupOn = false) }
        assertThat(store.get().codeGroupOn).isFalse()
        assertThat(store.get().wroteGroupOn).isTrue()
        assertThat(store.get().groupOn(AllowGroup.CODE)).isFalse()
    }

    private fun flagOn(block: () -> Unit) {
        ProviderTruthFlag.enabled = true
        block()
    }
}
