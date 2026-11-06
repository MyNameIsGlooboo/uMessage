package com.glooboo.umessage.ui.thread

import com.glooboo.umessage.helpers.SimName
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThreadSimLabelsTest {

    @Test
    fun aNicknameReplacesTheSimNumberOnADualSimPhone() {
        assertThat(simHeaderLabel(1, SimName("Work", ""), twoSims = true)).isEqualTo("Work")
    }

    @Test
    fun withoutANicknameTheHeaderKeepsTheNumber() {
        assertThat(simHeaderLabel(1, null, twoSims = true)).isEqualTo("2")
        assertThat(simHeaderLabel(0, SimName("", "0712"), twoSims = true)).isEqualTo("1")
    }

    @Test
    fun aSingleSimIgnoresTheNickname() {
        assertThat(simHeaderLabel(0, SimName("Work", ""), twoSims = false)).isEqualTo("1")
    }
}
