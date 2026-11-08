package com.glooboo.umessage.spam

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.ui.onboarding.welcomeFeatures
import com.glooboo.umessage.ui.settings.search.SettingsIndex
import com.glooboo.umessage.ui.settings.search.SettingsSearch
import com.glooboo.umessage.ui.settings.search.SettingsTarget
import com.glooboo.umessage.ui.thread.ThreadMenuInput
import com.glooboo.umessage.ui.thread.ThreadRecipients
import com.glooboo.umessage.ui.thread.buildThreadMenu
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Forwarding exists in every build, and the parts that follow the switch still obey it. Runs in every flavor. */
@RunWith(AndroidJUnit4::class)
class ForwardingAvailabilityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun forwardingExistsInEveryBuildGooglePlayIncluded() {
        assertThat(ForwardingAvailability.isAvailable(context)).isTrue()
    }

    @Test
    fun theThreadMenuHasNoAlwaysForwardWhenForwardingIsOff() {
        fun menu(available: Boolean) = buildThreadMenu(
            ThreadMenuInput(
                recipients = ThreadRecipients.fromNumbers(listOf("+15551234567")),
                hasItems = true,
                hasConversation = true,
                isArchived = false,
                isRecycleBin = false,
                archiveAvailable = false,
                singleNumberIsSaved = false,
                reportedSender = null,
                blockedSender = null,
                forwardingAvailable = available,
            ),
        )
        assertThat(menu(available = true).alwaysForward).isTrue()
        assertThat(menu(available = false).alwaysForward).isFalse()
    }

    @Test
    fun theWelcomePageDropsTheForwardingFeatureWhenForwardingIsOff() {
        val on = welcomeFeatures(forwarding = true).map { it.titleRes }
        val off = welcomeFeatures(forwarding = false).map { it.titleRes }
        assertThat(on).contains(R.string.onb_welcome_forwarding)
        assertThat(off).doesNotContain(R.string.onb_welcome_forwarding)
        assertThat(off).hasSize(on.size - 1)
    }

    @Test
    fun theSettingsSearchLeavesOutForwardingWhenForwardingIsOff() {
        val forwardingEntries = SettingsIndex.entries.filter { it.target in SettingsTarget.FORWARDING_TARGETS }
        assertThat(forwardingEntries).isNotEmpty()
        val title = context.getString(R.string.r3fwd_title)
        val on = SettingsSearch(context.resources, forwarding = true).find(title)
        val off = SettingsSearch(context.resources, forwarding = false).find(title)
        assertThat(on.map { it.entry.target }.filter { it in SettingsTarget.FORWARDING_TARGETS }).isNotEmpty()
        assertThat(off.map { it.entry.target }.filter { it in SettingsTarget.FORWARDING_TARGETS }).isEmpty()
    }
}
