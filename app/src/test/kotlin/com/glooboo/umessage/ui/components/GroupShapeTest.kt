package com.glooboo.umessage.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class GroupShapeTest {
    @Test
    fun `single row is rounded on both ends`() {
        assertEquals(RowCorners(28, 28), GroupShape.corners(0, 1))
    }

    @Test
    fun `first middle and last rows`() {
        assertEquals(RowCorners(28, 4), GroupShape.corners(0, 3))
        assertEquals(RowCorners(4, 4), GroupShape.corners(1, 3))
        assertEquals(RowCorners(4, 28), GroupShape.corners(2, 3))
    }
}
