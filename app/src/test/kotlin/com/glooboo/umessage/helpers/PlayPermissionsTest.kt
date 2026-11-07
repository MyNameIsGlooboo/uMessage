package com.glooboo.umessage.helpers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayPermissionsTest {
    @Test
    fun googlePlayBuildHasNoRecentPhotosRow() {
        assertFalse(PlayPermissions.recentPhotosAvailableFor(googlePlayBuild = true))
        assertTrue(PlayPermissions.recentPhotosAvailableFor(googlePlayBuild = false))
    }

    @Test
    fun googlePlayBuildNeverUsesOrOffersExactAlarms() {
        assertFalse(PlayPermissions.exactAlarmsDeclaredFor(googlePlayBuild = true))
        assertFalse(PlayPermissions.useExactAlarmFor(googlePlayBuild = true, systemAllows = true))
        assertFalse(PlayPermissions.useExactAlarmFor(googlePlayBuild = true, systemAllows = false))
    }

    @Test
    fun otherBuildsFollowTheSystemAnswer() {
        assertTrue(PlayPermissions.useExactAlarmFor(googlePlayBuild = false, systemAllows = true))
        assertFalse(PlayPermissions.useExactAlarmFor(googlePlayBuild = false, systemAllows = false))
    }
}
