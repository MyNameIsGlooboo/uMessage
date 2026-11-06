package com.glooboo.umessage.ui.thread

import com.glooboo.umessage.models.ThreadItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadHeaderDayTest {
    @Test
    fun theDayIsWrittenOncePerDayAndTheSimOnEveryHeader() {
        val noon = 1_700_000_000 // one day; 40 minutes later is the same day in any zone unless it crosses midnight
        val items = listOf(
            ThreadItem.ThreadDateTime(noon, "1"),
            ThreadItem.ThreadDateTime(noon + 60, "1"),
            ThreadItem.ThreadDateTime(noon + 120, "2"),
            ThreadItem.ThreadDateTime(noon + 3 * 86_400, "2"),
        )
        val headers = buildThreadRows(items, isGroup = false).filterIsInstance<ThreadRow.DateHeader>()
        assertTrue(headers[0].showDay && headers[0].showSim)
        assertTrue(headers.all { it.showSim })
        assertTrue(headers[3].showDay)
        assertEquals(4, headers.size)
    }
}
