package com.glooboo.umessage.helpers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailedSendAlertTest {
    @Test
    fun manualSendAlwaysAlerts() {
        assertTrue(FailedSendAlert.shouldAlert(automatic = false, alreadyShowing = false))
        assertTrue(FailedSendAlert.shouldAlert(automatic = false, alreadyShowing = true))
    }

    @Test
    fun firstAutomaticFailureAlerts() {
        assertTrue(FailedSendAlert.shouldAlert(automatic = true, alreadyShowing = false))
    }

    @Test
    fun repeatedAutomaticFailureIsQuiet() {
        assertFalse(FailedSendAlert.shouldAlert(automatic = true, alreadyShowing = true))
    }
}
