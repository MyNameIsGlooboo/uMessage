package com.glooboo.umessage.helpers

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.NotificationSmartReplies.Gate
import com.glooboo.umessage.helpers.smartreply.SmartReplyPrefs
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** The gating of the suggested-reply buttons of the message notification, and the defaults of their switches. */
@RunWith(AndroidJUnit4::class)
class NotificationSmartRepliesTest {

    private val now = 1_000_000_000L
    private val ok = Gate(isMms = false, isGroup = false, knownSender = true, hiddenChat = false)

    private fun offer(
        gate: Gate = ok,
        address: String = "+15550100",
        body: String = "Are you coming?",
        chips: Boolean = true,
        inNotifications: Boolean = true,
    ) = NotificationSmartReplies.shouldOffer(chips, inNotifications, gate, address, body, now)

    @Test
    fun aPlainTextFromAKnownPersonQualifies() {
        assertThat(offer()).isTrue()
    }

    @Test
    fun eitherSwitchOffMeansNoButtons() {
        assertThat(offer(chips = false)).isFalse()
        assertThat(offer(inNotifications = false)).isFalse()
    }

    @Test
    fun mmsGroupsUnknownSendersAndHiddenChatsGetNone() {
        assertThat(offer(ok.copy(isMms = true))).isFalse()
        assertThat(offer(ok.copy(isGroup = true))).isFalse()
        assertThat(offer(ok.copy(knownSender = false))).isFalse()
        assertThat(offer(ok.copy(hiddenChat = true))).isFalse()
    }

    @Test
    fun serviceSendersLinksCodesAndLongTextsGetNone() {
        assertThat(offer(address = "Bank")).isFalse()
        assertThat(offer(body = "See https://example.com/x")).isFalse()
        assertThat(offer(body = "Your code is 482913")).isFalse()
        assertThat(offer(body = "a".repeat(200))).isFalse()
        assertThat(offer(body = "")).isFalse()
    }

    @Test
    fun theChipsAreTheBestRepliesWithoutBlanksOrRepeats() {
        val replies = listOf("Yes", "", "No", "Maybe", "Yes")
        assertThat(NotificationSmartReplies.pick(replies, 3)).containsExactly("Yes", "No", "Maybe").inOrder()
        assertThat(NotificationSmartReplies.pick(replies, 1)).containsExactly("Yes")
        assertThat(NotificationSmartReplies.pick(replies, 0)).isEmpty()
    }

    @Test
    fun bothSwitchesAreOnUntilTheUserTurnsThemOff() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val prefs = SmartReplyPrefs(context.getSharedPreferences("smart_reply_prefs_test", Context.MODE_PRIVATE))

        assertThat(prefs.enabled).isTrue()
        assertThat(prefs.notificationsEnabled).isTrue()

        prefs.notificationsEnabled = false
        assertThat(prefs.notificationsEnabled).isFalse()
        assertThat(prefs.enabled).isTrue()

        prefs.enabled = false
        assertThat(prefs.enabled).isFalse()
    }
}
