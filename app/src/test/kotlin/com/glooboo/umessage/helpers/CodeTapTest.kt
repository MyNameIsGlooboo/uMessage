package com.glooboo.umessage.helpers

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Tap to copy: only a notification of the "Verification codes" channel copies the code on tap. */
class CodeTapTest {

    @Test
    fun aCodeInAnOrdinaryChatIsInTheCodeChannel() {
        assertThat(CodeTap.inCodeChannel("482913", muted = false, hasCustomNotifications = false)).isTrue()
    }

    @Test
    fun noCodeMeansNoCodeChannel() {
        assertThat(CodeTap.inCodeChannel(null, muted = false, hasCustomNotifications = false)).isFalse()
    }

    @Test
    fun aMutedChatOrOneWithItsOwnNotificationSettingsKeepsItsChannel() {
        assertThat(CodeTap.inCodeChannel("482913", muted = true, hasCustomNotifications = false)).isFalse()
        assertThat(CodeTap.inCodeChannel("482913", muted = false, hasCustomNotifications = true)).isFalse()
    }

    @Test
    fun theTapCopiesOnlyWhenTheSettingIsOnAndTheNotificationIsInTheCodeChannel() {
        assertThat(CodeTap.copiesOnTap("482913", enabled = true, inCodeChannel = true)).isTrue()
        assertThat(CodeTap.copiesOnTap("482913", enabled = false, inCodeChannel = true)).isFalse()
        assertThat(CodeTap.copiesOnTap("482913", enabled = true, inCodeChannel = false)).isFalse()
        assertThat(CodeTap.copiesOnTap(null, enabled = true, inCodeChannel = true)).isFalse()
        assertThat(CodeTap.copiesOnTap("", enabled = true, inCodeChannel = true)).isFalse()
    }
}
