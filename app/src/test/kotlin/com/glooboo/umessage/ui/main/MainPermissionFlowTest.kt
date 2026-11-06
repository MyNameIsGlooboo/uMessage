package com.glooboo.umessage.ui.main

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The decisions of the default-SMS and permission flow, without Android (MC-04, MC-05, CM-05). */
class MainPermissionFlowTest {

    private val handle = SavedStateHandle()
    private val flow = MainPermissionFlow(handle)

    /** Runs the flow up to a showing list, as the screen does when everything is granted. */
    private fun MainPermissionFlow.reachList() {
        assertThat(start()).isEqualTo(PermissionStep.RequestRole)
        onRoleRequested()
        assertThat(onRoleResult(isDefaultSmsApp = true)).isEqualTo(PermissionStep.AskPermissions)
        onSmsPermissionsGranted()
        assertThat(startListOnce()).isTrue()
    }

    @Test
    fun aFreshScreenAsksForTheRoleFirst() {
        assertThat(flow.start()).isEqualTo(PermissionStep.RequestRole)
        assertThat(flow.canRequestRole).isTrue()
    }

    @Test
    fun anOpenRoleDialogIsNotAskedAgainAfterRotation() {
        flow.start()
        flow.onRoleRequested()
        assertThat(flow.canRequestRole).isFalse()

        // The same view model, or a new one restored from the saved state after the process was killed.
        assertThat(flow.start()).isEqualTo(PermissionStep.None)
        val restored = MainPermissionFlow(handle)
        assertThat(restored.start()).isEqualTo(PermissionStep.None)
        assertThat(restored.canRequestRole).isFalse()
    }

    @Test
    fun decliningTheRoleExplainsAndBecomingDefaultInSettingsGoesOn() {
        flow.start()
        flow.onRoleRequested()
        assertThat(flow.onRoleResult(isDefaultSmsApp = false)).isEqualTo(PermissionStep.None)
        assertThat(flow.needsDefaultApp).isTrue()
        assertThat(flow.canRequestRole).isTrue()
        assertThat(MainPermissionFlow(handle).needsDefaultApp).isTrue()
        assertThat(flow.start()).isEqualTo(PermissionStep.None)

        assertThat(flow.recheck(isDefaultSmsApp = false, hasSmsPermissions = true)).isEqualTo(PermissionStep.None)
        assertThat(flow.recheck(isDefaultSmsApp = true, hasSmsPermissions = true)).isEqualTo(PermissionStep.RequestRole)
        assertThat(flow.needsDefaultApp).isFalse()
    }

    @Test
    fun aDeniedSmsPermissionExplainsUntilItIsGranted() {
        flow.start()
        flow.onSmsPermissionDenied()
        assertThat(flow.missingSmsPermission).isTrue()
        assertThat(MainPermissionFlow(handle).missingSmsPermission).isTrue()
        assertThat(flow.start()).isEqualTo(PermissionStep.Recheck)

        assertThat(flow.recheck(isDefaultSmsApp = true, hasSmsPermissions = false)).isEqualTo(PermissionStep.None)
        assertThat(flow.recheck(isDefaultSmsApp = true, hasSmsPermissions = true))
            .isEqualTo(PermissionStep.AskPermissions)
        flow.onSmsPermissionsGranted()
        assertThat(flow.missingSmsPermission).isFalse()
    }

    @Test
    fun theListStartsOnce() {
        flow.reachList()
        assertThat(flow.startListOnce()).isFalse()
    }

    @Test
    fun theNotDefaultBannerShowsOnlyOverAStartedList() {
        flow.updateNotDefaultBanner(isDefaultSmsApp = false)
        assertThat(flow.notDefaultSmsApp).isFalse()

        flow.reachList()
        flow.updateNotDefaultBanner(isDefaultSmsApp = true)
        assertThat(flow.notDefaultSmsApp).isFalse()
        flow.updateNotDefaultBanner(isDefaultSmsApp = false)
        assertThat(flow.notDefaultSmsApp).isTrue()

        // Fixing it from the banner: the dialog is open, then answered.
        flow.onRoleRequested()
        flow.updateNotDefaultBanner(isDefaultSmsApp = false)
        assertThat(flow.notDefaultSmsApp).isFalse()
        flow.onRoleResult(isDefaultSmsApp = true)
        assertThat(flow.notDefaultSmsApp).isFalse()
    }

    @Test
    fun refusedNotificationsShowTheDialogUntilANewScreenStarts() {
        flow.onNotificationPermission(granted = true)
        assertThat(flow.showNotificationDialog).isFalse()
        flow.onNotificationPermission(granted = false)
        assertThat(flow.showNotificationDialog).isTrue()
        flow.start()
        assertThat(flow.showNotificationDialog).isFalse()
    }
}
