package com.glooboo.umessage.ui.setup

import com.glooboo.umessage.helpers.BackgroundStatus
import com.glooboo.umessage.helpers.OemStepKind
import com.glooboo.umessage.helpers.StepState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Setup screen's status words: what Android verifies, what only the user can vouch for. */
class SetupStatusTest {

    private val free = BackgroundStatus(
        ignoringBatteryOptimizations = true,
        backgroundRestricted = false,
        restrictedBucket = false,
    )
    private val restricted = free.copy(ignoringBatteryOptimizations = false, backgroundRestricted = true)

    @Test
    fun `a permission or role is either done or not done`() {
        assertEquals(SetupMark.DONE, checkableMark(true))
        assertEquals(SetupMark.NOT_DONE, checkableMark(false))
    }

    @Test
    fun `a verified step follows Android`() {
        assertEquals(
            SetupMark.DONE,
            oemStepMark(OemStepKind.IGNORE_BATTERY, StepState.DONE_VERIFIED, free, markedByUser = false),
        )
        assertEquals(
            SetupMark.NOT_DONE,
            oemStepMark(OemStepKind.IGNORE_BATTERY, StepState.NOT_DONE, restricted, markedByUser = false),
        )
    }

    @Test
    fun `a step the user marked says so and is not called verified`() {
        assertEquals(
            SetupMark.MARKED_BY_YOU,
            oemStepMark(OemStepKind.XIAOMI_AUTOSTART, StepState.DONE_BY_USER, free, markedByUser = true),
        )
    }

    @Test
    fun `a step Android cannot read is cannot-check until marked`() {
        assertEquals(
            SetupMark.CANNOT_CHECK,
            oemStepMark(OemStepKind.XIAOMI_AUTOSTART, StepState.NOT_DONE, free, markedByUser = false),
        )
        assertEquals(
            SetupMark.CANNOT_CHECK,
            oemStepMark(OemStepKind.HUAWEI_LAUNCH, StepState.NOT_DONE, restricted, markedByUser = false),
        )
    }

    @Test
    fun `a limited app is not done even for a step Android cannot read`() {
        assertEquals(
            SetupMark.NOT_DONE,
            oemStepMark(OemStepKind.SAMSUNG_SLEEPING, StepState.NOT_DONE, restricted, markedByUser = false),
        )
        assertEquals(
            SetupMark.NOT_DONE,
            oemStepMark(OemStepKind.SAMSUNG_SLEEPING, StepState.NOT_DONE, restricted, markedByUser = true),
        )
    }

    @Test
    fun `a standby bucket limit counts as a background limit`() {
        val bucket = free.copy(restrictedBucket = true)
        assertEquals(
            SetupMark.NOT_DONE,
            oemStepMark(OemStepKind.VIVO_BACKGROUND, StepState.NOT_DONE, bucket, markedByUser = false),
        )
    }

    @Test
    fun `the summary needs every required row verified and ignores optional ones`() {
        val done = SetupRowState(SetupMark.DONE, required = true)
        assertTrue(setupRequiredDone(listOf(done, SetupRowState(SetupMark.NOT_DONE, required = false))))
        assertFalse(setupRequiredDone(listOf(done, SetupRowState(SetupMark.NOT_DONE, required = true))))
        assertFalse(setupRequiredDone(listOf(done, SetupRowState(SetupMark.MARKED_BY_YOU, required = true))))
        assertTrue(setupRequiredDone(emptyList()))
    }

    // B2: the Setup row on the main list says so (with a dot) exactly when the page has something to fix.
    @Test
    fun `the setup row stays quiet when everything required is done and the battery is fine`() {
        val done = SetupRowState(SetupMark.DONE, required = true)
        assertFalse(setupNeedsAttention(listOf(done), batteryOpen = false))
    }

    @Test
    fun `the setup row asks attention when the default SMS role is not held`() {
        assertTrue(setupNeedsAttention(listOf(SetupRowState(SetupMark.NOT_DONE, required = true)), batteryOpen = false))
    }

    @Test
    fun `the setup row asks attention when a required permission is denied`() {
        val role = SetupRowState(SetupMark.DONE, required = true)
        val permission = SetupRowState(SetupMark.NOT_DONE, required = true)
        assertTrue(setupNeedsAttention(listOf(role, permission), batteryOpen = false))
    }

    @Test
    fun `the setup row asks attention while Android reports a background limit`() {
        assertTrue(setupNeedsAttention(listOf(SetupRowState(SetupMark.DONE, required = true)), batteryOpen = true))
    }

    @Test
    fun `an optional row left open never asks for attention`() {
        val done = SetupRowState(SetupMark.DONE, required = true)
        val optional = SetupRowState(SetupMark.NOT_DONE, required = false)
        assertFalse(setupNeedsAttention(listOf(done, optional), batteryOpen = false))
    }
}
