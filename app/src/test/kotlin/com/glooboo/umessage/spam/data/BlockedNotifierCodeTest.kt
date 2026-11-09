package com.glooboo.umessage.spam.data

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** "Hide codes on the lock screen" also hides the code in the notification of a held message. */
class BlockedNotifierCodeTest {

    @Test
    fun theCodeIsMaskedWhenHidingIsOn() {
        assertThat(BlockedNotifier.shownBody("Your code is 482913", hideCodes = true)).isEqualTo("Your code is ••••")
        assertThat(BlockedNotifier.shownBody("Your password: x7Kq2m", hideCodes = true))
            .isEqualTo("Your password: ••••")
    }

    @Test
    fun theTextIsUntouchedWhenHidingIsOffOrThereIsNoCode() {
        assertThat(BlockedNotifier.shownBody("Your code is 482913", hideCodes = false)).isEqualTo("Your code is 482913")
        assertThat(BlockedNotifier.shownBody("Meet at 1830", hideCodes = true)).isEqualTo("Meet at 1830")
    }
}
