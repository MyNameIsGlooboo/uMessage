package com.glooboo.umessage.ui.conversations

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Owner notes r3: the New conversation FAB folds on a downward scroll and grows back on an upward one. */
class FabScrollDirectionTest {

    @Test
    fun aNewFirstItemDecidesTheDirectionAtOnce() {
        assertThat(fabScrollDirection(3, 200, 4, 0, jitterPx = 20)).isEqualTo(FabScrollDirection.DOWN)
        assertThat(fabScrollDirection(4, 0, 3, 200, jitterPx = 20)).isEqualTo(FabScrollDirection.UP)
    }

    @Test
    fun withinOneItemTheOffsetDecidesIt() {
        assertThat(fabScrollDirection(2, 10, 2, 60, jitterPx = 20)).isEqualTo(FabScrollDirection.DOWN)
        assertThat(fabScrollDirection(2, 60, 2, 10, jitterPx = 20)).isEqualTo(FabScrollDirection.UP)
    }

    @Test
    fun tinyMovesAreIgnored() {
        assertThat(fabScrollDirection(2, 50, 2, 50, jitterPx = 20)).isEqualTo(FabScrollDirection.NONE)
        assertThat(fabScrollDirection(2, 50, 2, 65, jitterPx = 20)).isEqualTo(FabScrollDirection.NONE)
        assertThat(fabScrollDirection(2, 50, 2, 35, jitterPx = 20)).isEqualTo(FabScrollDirection.NONE)
    }
}
