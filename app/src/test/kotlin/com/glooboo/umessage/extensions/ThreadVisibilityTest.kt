package com.glooboo.umessage.extensions

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Q8: a group chat is not hidden from Messages just because one member is blocked. */
class ThreadVisibilityTest {

    private val blocked = setOf("+1555000")
    private fun hidden(vararg numbers: String) = isThreadHiddenByBlocks(numbers.toList()) { it in blocked }

    @Test fun aOneToOneChatWithABlockedNumberIsHidden() {
        assertThat(hidden("+1555000")).isTrue()
    }

    @Test fun aOneToOneChatWithAnUnblockedNumberIsShown() {
        assertThat(hidden("+1555111")).isFalse()
    }

    @Test fun aGroupWithOneBlockedMemberIsShown() {
        assertThat(hidden("+1555111", "+1555000", "+1555222")).isFalse()
    }

    @Test fun aGroupWhereEveryMemberIsBlockedIsHidden() {
        assertThat(isThreadHiddenByBlocks(listOf("+1555000", "+1555000")) { it in blocked }).isTrue()
    }

    @Test fun aThreadWithoutNumbersIsHidden() {
        assertThat(hidden()).isTrue()
    }
}
