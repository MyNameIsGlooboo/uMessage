package com.glooboo.umessage.helpers

import com.glooboo.umessage.ui.components.jalaliListDate
import com.glooboo.umessage.ui.main.ChecklistStep
import com.glooboo.umessage.ui.main.ChecklistTitle
import com.glooboo.umessage.ui.main.checklistComplete
import com.glooboo.umessage.ui.main.checklistProgress
import com.glooboo.umessage.ui.main.checklistTitle
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class W3cPrefsTest {

    @Test
    fun `date style falls back to the old Jalali switch`() {
        assertEquals(DateStyle.JALALI, DateStyle.resolve(null, legacyJalali = true))
        assertEquals(DateStyle.GREGORIAN, DateStyle.resolve(null, legacyJalali = false))
    }

    @Test
    fun `a stored date style wins over the old switch`() {
        assertEquals(DateStyle.GREGORIAN, DateStyle.resolve("gregorian", legacyJalali = true))
        assertEquals(DateStyle.BOTH, DateStyle.resolve("both", legacyJalali = false))
        assertEquals(DateStyle.GREGORIAN, DateStyle.resolve("nonsense", legacyJalali = false))
    }

    @Test
    fun `font scale multiplies the system scale`() {
        assertEquals(1f, appFontScale(1f, TextSize.DEFAULT), 0.0001f)
        assertEquals(1.3f, appFontScale(1f, TextSize.LARGEST), 0.0001f)
        assertEquals(1.5f * 0.9f, appFontScale(1.5f, TextSize.SMALL), 0.0001f)
        assertEquals(1.6f, appFontScale(1f, TextSize.GIANT), 0.0001f)
    }

    @Test
    fun `the app share of the font scale stops at 2 but the system scale is kept`() {
        assertEquals(2f, appFontScale(2f, TextSize.LARGEST), 0.0001f)
        assertEquals(2f, appFontScale(1.5f, TextSize.GIANT), 0.0001f)
        assertEquals(2.5f, appFontScale(2.5f, TextSize.DEFAULT), 0.0001f)
        assertEquals(3f, appFontScale(3f, TextSize.GIANT), 0.0001f)
    }

    @Test
    fun `text size levels keep their order keys and scales`() {
        assertEquals(
            listOf("xsmall", "small", "default", "large", "largest", "huge", "giant"),
            TextSize.entries.map { it.key },
        )
        assertEquals(
            listOf(0.8f, 0.9f, 1f, 1.15f, 1.3f, 1.45f, 1.6f),
            TextSize.entries.map { it.scale },
        )
    }

    @Test
    fun `text size keys round trip and unknown keys mean default`() {
        TextSize.values().forEach { assertEquals(it, TextSize.fromKey(it.key)) }
        assertEquals(TextSize.DEFAULT, TextSize.fromKey(null))
        assertEquals(TextSize.DEFAULT, TextSize.fromKey("enormous"))
        assertEquals(TextSize.GIANT, TextSize.fromKey("giant"))
    }

    @Test
    fun `jalali list dates drop the current year`() {
        val today = LocalDate.of(2026, 11, 2)
        assertEquals("7 Aban", jalaliListDate(LocalDate.of(2026, 10, 29), today, persian = false))
        assertEquals("1 Farvardin 1404", jalaliListDate(LocalDate.of(2025, 3, 21), today, persian = false))
        assertEquals("۷ آبان", jalaliListDate(LocalDate.of(2026, 10, 29), today, persian = true))
    }

    @Test
    fun `checklist is complete without the optional step`() {
        val required = setOf(ChecklistStep.DefaultSms, ChecklistStep.Notifications, ChecklistStep.BlockedTab)
        assertTrue(checklistComplete(required))
        assertFalse(checklistComplete(setOf(ChecklistStep.DefaultSms, ChecklistStep.Forwarding)))
        assertEquals(3 to 4, checklistProgress(required))
    }

    @Test
    fun `checklist says all set only when every step is done`() {
        val required = setOf(ChecklistStep.DefaultSms, ChecklistStep.Notifications, ChecklistStep.BlockedTab)
        assertEquals(ChecklistTitle.Steps, checklistTitle(setOf(ChecklistStep.DefaultSms)))
        assertEquals(ChecklistTitle.BasicsDone, checklistTitle(required))
        assertEquals(ChecklistTitle.AllDone, checklistTitle(ChecklistStep.entries.toSet()))
    }
}
