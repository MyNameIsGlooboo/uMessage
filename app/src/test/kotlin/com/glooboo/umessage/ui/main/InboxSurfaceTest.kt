package com.glooboo.umessage.ui.main

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class InboxSurfaceTest {

    private val page = Color(0xFFFFFBFE)
    private val container = Color(0xFFF3EDF7)
    private val onSurface = Color(0xFF1C1B1F)

    @Test
    fun theContainerIsTheTopToneWhenTextReadsOnIt() {
        assertEquals(container, inboxTopColor(container, page, onSurface))
    }

    @Test
    fun thePageBackgroundIsUsedWhenTextWouldVanishOnTheContainer() {
        val midTone = Color(0xFF8A8A8A)
        assertEquals(page, inboxTopColor(midTone, page, midTone))
    }
}
