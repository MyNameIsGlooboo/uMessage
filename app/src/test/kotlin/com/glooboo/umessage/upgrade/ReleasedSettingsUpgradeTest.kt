package com.glooboo.umessage.upgrade

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.NavPrefs
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.UnknownInboxNotify
import com.glooboo.umessage.spam.UnknownSenderMode
import com.glooboo.umessage.spam.data.SpamSettingsStore
import com.glooboo.umessage.ui.lock.LockScope
import com.glooboo.umessage.ui.onboarding.OnboardingState
import com.glooboo.umessage.ui.spam.BlockingMigration
import com.glooboo.umessage.ui.spam.BlockingMigrationStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * J13 (upgrade safety): the preferences as each released version wrote them, read by this version. Every key and value
 * below is written out as the old code stored it (see the version table in the J13 review), not through today's
 * setters, so a renamed key or a changed reading fails here. Robolectric gives each test fresh prefs. Made-up data.
 *
 * Spam settings, file `spamless_spam_settings`: 0.3.0 to 0.4.4 also stored `otp_exemption`; 0.4.5 to 0.4.14 the same
 * keys without it; none of them stored `filter_on` or `provider_truth_mapped`.
 */
@RunWith(AndroidJUnit4::class)
class ReleasedSettingsUpgradeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    /** The spam settings file as an older version left it. */
    private fun storedSpamSettings(
        mode: String,
        purgeDays: Int = 30,
        notify: Boolean = false,
        unknownNotify: String = "NOTIFY",
        openLink: Boolean = false,
        region: String = "",
        otpExemption: Boolean? = null,
    ) {
        prefs(SPAM_PREFS).edit().apply {
            putString("mode", mode)
            putInt("purge_after_days", purgeDays)
            putBoolean("notify_on_block", notify)
            putString("unknown_inbox_notify", unknownNotify)
            putBoolean("open_link_action", openLink)
            putString("block_outside_region", region)
            putLong("last_purge_at", 1_700_000_000_000L)
            otpExemption?.let { putBoolean("otp_exemption", it) }
        }.commit()
    }

    private fun store(legacySwitch: Boolean = false) = SpamSettingsStore(context) { legacySwitch }

    // ---- spam mode -> the one switch (7.1), and the other spam settings ------------------------------------------

    @Test
    fun aStrictUserOf030KeepsEverySettingAndGetsTheFilterOn() {
        storedSpamSettings(
            mode = "STRICT", purgeDays = 14, notify = true, unknownNotify = "SILENT", openLink = true, region = "US",
            otpExemption = false,
        )

        val before = store().get()
        assertThat(before.filterOn).isTrue()
        assertThat(before.mode).isEqualTo(UnknownSenderMode.STRICT)
        // S5: until the one-time step ran, auto-delete reads "keep forever".
        assertThat(before.purgeAfterDays).isEqualTo(0)

        assertThat(store().applyProviderTruthMapping()).isTrue()

        val after = store().get()
        assertThat(after.filterOn).isTrue()
        assertThat(after.mode).isEqualTo(UnknownSenderMode.STRICT)
        assertThat(after.purgeAfterDays).isEqualTo(0)
        assertThat(after.notifyOnBlock).isTrue()
        assertThat(after.unknownInboxNotify).isEqualTo(UnknownInboxNotify.SILENT)
        assertThat(after.openLinkAction).isTrue()
        assertThat(after.blockOutsideRegion).isEqualTo("US")
        // The old keys stay on disk for a downgrade; the one-time step wrote only its own three.
        assertThat(prefs(SPAM_PREFS).getString("mode", null)).isEqualTo("STRICT")
        assertThat(prefs(SPAM_PREFS).getBoolean("otp_exemption", true)).isFalse()
        assertThat(prefs(SPAM_PREFS).getBoolean("filter_on", false)).isTrue()
        assertThat(prefs(SPAM_PREFS).getBoolean("provider_truth_mapped", false)).isTrue()
    }

    @Test
    fun aSmartUserOf0414GetsTheFilterOnAndKeepForever() {
        storedSpamSettings(mode = "SMART", purgeDays = 30, notify = false, unknownNotify = "NOTIFY")

        assertThat(store().applyProviderTruthMapping()).isTrue()

        val after = store().get()
        assertThat(after.filterOn).isTrue()
        assertThat(after.purgeAfterDays).isEqualTo(0)
        assertThat(after.notifyOnBlock).isFalse()
        assertThat(after.unknownInboxNotify).isEqualTo(UnknownInboxNotify.NOTIFY)
    }

    @Test
    fun anOffUserOf0414KeepsTheFilterOffAndTheNotificationChoice() {
        storedSpamSettings(mode = "OFF", purgeDays = 7, notify = true, unknownNotify = "NONE")

        // Read before the one-time step (the first text after the update): already off.
        assertThat(store().get().filterOn).isFalse()
        assertThat(store().applyProviderTruthMapping()).isTrue()

        val after = store().get()
        assertThat(after.filterOn).isFalse()
        assertThat(after.mode).isEqualTo(UnknownSenderMode.OFF)
        assertThat(after.purgeAfterDays).isEqualTo(0)
        assertThat(after.notifyOnBlock).isTrue()
        assertThat(after.unknownInboxNotify).isEqualTo(UnknownInboxNotify.NONE)
        // A second start does nothing again.
        assertThat(store().applyProviderTruthMapping()).isFalse()
    }

    /**
     * 0.3.0 to 0.4.12 kept the old Fossify "Block unknown numbers" switch, which made Off act as Strict. The start-up
     * migration (BlockingMigration) runs before the first run's settings step and must leave the filter on.
     */
    @Test
    fun anOffUserWithTheOldSwitchOnEndsWithTheFilterOn() = runBlocking<Unit> {
        storedSpamSettings(mode = "OFF")
        var switchOn = true
        val legacy = object : BlockingMigrationStore {
            override fun keywords(): Set<String> = emptySet()
            override fun removeKeywords(words: Collection<String>) = Unit
            override fun blockUnknownSwitch(): Boolean = switchOn
            override fun clearBlockUnknownSwitch() {
                switchOn = false
            }
        }
        val settings = SpamSettingsStore(context) { switchOn }
        // Before the start-up migration ran, the reader already counts the old switch.
        assertThat(settings.get().filterOn).isTrue()

        BlockingMigration.run(
            store = legacy,
            existingRules = { emptyList<SpamRule>() },
            addRule = {},
            updateMode = { change -> settings.update { it.copy(mode = change(it.mode)) } },
        )
        assertThat(switchOn).isFalse()
        assertThat(settings.applyProviderTruthMapping()).isTrue()

        assertThat(settings.get().mode).isEqualTo(UnknownSenderMode.STRICT)
        assertThat(settings.get().filterOn).isTrue()
    }

    @Test
    fun anUnreadableOldModeCountsAsOn() {
        storedSpamSettings(mode = "SOMETHING_OLDER")

        assertThat(store().applyProviderTruthMapping()).isTrue()
        assertThat(store().get().filterOn).isTrue()
        assertThat(store().get().mode).isEqualTo(UnknownSenderMode.SMART)
    }

    // ---- welcome, lock, forwarding -------------------------------------------------------------------------------

    /** Every release stored the finished welcome under the same key: an updated user is not sent through it again. */
    @Test
    fun theFinishedWelcomeOfEveryReleaseIsStillFinished() {
        assertThat(OnboardingState.isCompleted(context)).isFalse()
        prefs("spamless_onboarding").edit().putBoolean("completed", true).commit()

        assertThat(OnboardingState.isCompleted(context)).isTrue()
    }

    @Test
    fun theLockScopeOfEveryReleaseIsReadTheSame() {
        assertThat(LockScope.current(context)).isEqualTo(LockScope.WholeApp)

        prefs("spamless_lock_scope").edit().putString("scope", "chosen").commit()
        assertThat(LockScope.current(context)).isEqualTo(LockScope.ChosenParts)
        // Fossify's app lock switch, unchanged since 0.3.0: with "chosen parts" it covers Spam (U1, SpamChatGate).
        assertThat(LockScope.blockedLocked(context)).isFalse()
        prefs(FOSSIFY_PREFS).edit().putBoolean("app_password_protection", true).commit()
        assertThat(LockScope.blockedLocked(context)).isTrue()

        prefs("spamless_lock_scope").edit().putString("scope", "whole").commit()
        assertThat(LockScope.current(context)).isEqualTo(LockScope.WholeApp)
        assertThat(LockScope.blockedLocked(context)).isFalse()
    }

    @Test
    fun aForwardPauseOfAnOlderReleaseStaysOn() {
        prefs("spamless_nav").edit().putBoolean("forward_paused", true).commit()

        assertThat(NavPrefs.get(context).forwardPaused).isTrue()
    }

    private companion object {
        const val SPAM_PREFS = "spamless_spam_settings"

        /** org.fossify.commons.helpers.PREFS_KEY, the file of Fossify's BaseConfig. */
        const val FOSSIFY_PREFS = "Prefs"
    }
}
