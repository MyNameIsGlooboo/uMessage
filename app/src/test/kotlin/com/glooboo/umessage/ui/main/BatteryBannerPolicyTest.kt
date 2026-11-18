package com.glooboo.umessage.ui.main

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.BackgroundStatus
import com.glooboo.umessage.helpers.Oem
import com.glooboo.umessage.helpers.OemHelper
import com.glooboo.umessage.helpers.OemStepKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** When the battery banner shows on the Messages tab, and its four-week "Not now". */
@RunWith(AndroidJUnit4::class)
class BatteryBannerPolicyTest {

    private val free = BackgroundStatus(
        ignoringBatteryOptimizations = true,
        backgroundRestricted = false,
        restrictedBucket = false,
    )
    private val optimized = free.copy(ignoringBatteryOptimizations = false)
    private val restricted = optimized.copy(backgroundRestricted = true)
    private val day = 24L * 60 * 60 * 1000

    private fun needed(oem: Oem, status: BackgroundStatus, marked: Set<OemStepKind> = emptySet()) =
        BatteryBannerPolicy.needed(oem, OemHelper.steps("com.example.under.test", "uMessage", oem), status) {
            it in marked
        }

    @Test
    fun `a plain phone that is not restricted never sees it`() {
        assertFalse(needed(Oem.OTHER, optimized))
        assertFalse(needed(Oem.OTHER, free))
    }

    @Test
    fun `any phone Android restricts sees it`() {
        assertTrue(needed(Oem.OTHER, restricted))
        assertTrue(needed(Oem.OTHER, optimized.copy(restrictedBucket = true)))
    }

    @Test
    fun `a vendor phone sees it until battery and its own steps are done`() {
        assertTrue(needed(Oem.XIAOMI, optimized))
        assertTrue(needed(Oem.XIAOMI, free))
        assertFalse(
            needed(Oem.XIAOMI, free, setOf(OemStepKind.XIAOMI_AUTOSTART, OemStepKind.XIAOMI_BATTERY_SAVER)),
        )
        assertTrue(needed(Oem.SAMSUNG, free))
        assertFalse(needed(Oem.SAMSUNG, free, setOf(OemStepKind.SAMSUNG_SLEEPING)))
    }

    @Test
    fun `every known vendor is covered`() {
        listOf(Oem.XIAOMI, Oem.SAMSUNG, Oem.HUAWEI, Oem.OPPO_FAMILY, Oem.VIVO).forEach { assertTrue(needed(it, free)) }
    }

    @Test
    fun `a limit that Android still reports keeps it even after the user marked the steps`() {
        val marked = setOf(OemStepKind.SAMSUNG_SLEEPING, OemStepKind.IGNORE_BATTERY)
        assertTrue(needed(Oem.SAMSUNG, restricted, marked))
    }

    @Test
    fun `Not now hides it for 28 days only`() {
        val at = 100 * day
        assertFalse(BatteryBannerPolicy.dismissed(now = at + day, dismissedAt = 0L))
        assertTrue(BatteryBannerPolicy.dismissed(now = at + day, dismissedAt = at))
        assertTrue(BatteryBannerPolicy.dismissed(now = at + 27 * day, dismissedAt = at))
        assertFalse(BatteryBannerPolicy.dismissed(now = at + 28 * day, dismissedAt = at))
        // A clock set back never hides it for good.
        assertFalse(BatteryBannerPolicy.dismissed(now = at - day, dismissedAt = at))
        assertEquals(28 * day, BatteryBannerPolicy.DISMISS_MS)
    }
}
