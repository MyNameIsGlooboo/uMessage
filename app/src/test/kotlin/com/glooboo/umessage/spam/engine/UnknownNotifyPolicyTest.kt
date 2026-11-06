package com.glooboo.umessage.spam.engine

import com.glooboo.umessage.helpers.NotifyMode
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.UnknownInboxNotify
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** I03: reason + setting -> how loud the notification is. */
class UnknownNotifyPolicyTest {

    private fun mode(reason: DecisionReason?, setting: UnknownInboxNotify, hasCode: Boolean = false) =
        UnknownNotifyPolicy.modeFor(reason, setting, hasCode)

    @Test
    fun notifyKeepsTodaysBehaviourForEveryone() {
        DecisionReason.entries.forEach { assertThat(mode(it, UnknownInboxNotify.NOTIFY)).isEqualTo(NotifyMode.NORMAL) }
        assertThat(mode(null, UnknownInboxNotify.NOTIFY)).isEqualTo(NotifyMode.NORMAL)
    }

    @Test
    fun unknownSendersFollowTheSetting() {
        listOf(DecisionReason.MODE_OFF, DecisionReason.CONTACTS_UNAVAILABLE).forEach {
            assertThat(mode(it, UnknownInboxNotify.SILENT)).isEqualTo(NotifyMode.SILENT)
            assertThat(mode(it, UnknownInboxNotify.NONE)).isEqualTo(NotifyMode.NONE)
        }
    }

    @Test
    fun knownSendersAndFilterFailuresAreNeverSilenced() {
        listOf(
            DecisionReason.CONTACT, DecisionReason.ALLOW_RULE, DecisionReason.KNOWN_CONVERSATION,
            DecisionReason.OTP_EXEMPTION, null,
        ).forEach {
            assertThat(mode(it, UnknownInboxNotify.NONE)).isEqualTo(NotifyMode.NORMAL)
            assertThat(mode(it, UnknownInboxNotify.SILENT)).isEqualTo(NotifyMode.NORMAL)
        }
    }

    @Test
    fun verificationCodesAreNeverSilenced() {
        assertThat(mode(DecisionReason.MODE_OFF, UnknownInboxNotify.NONE, hasCode = true)).isEqualTo(NotifyMode.NORMAL)
        assertThat(mode(DecisionReason.MODE_OFF, UnknownInboxNotify.SILENT, hasCode = true))
            .isEqualTo(NotifyMode.NORMAL)
    }

    @Test
    fun knownAndUnknownDoNotOverlap() {
        DecisionReason.entries.forEach {
            assertThat(UnknownNotifyPolicy.isKnown(it) && UnknownNotifyPolicy.isUnknown(it)).isFalse()
        }
    }
}
