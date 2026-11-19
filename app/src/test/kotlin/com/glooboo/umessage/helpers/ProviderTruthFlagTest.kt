package com.glooboo.umessage.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderTruthFlagTest {

    @Test fun theShippedDefaultIsOff() {
        assertEquals(false, ProviderTruthFlag.SHIPPED_DEFAULT)
        assertFalse(ProviderTruthFlag.enabled)
    }

    @Test fun aTestCanSwitchItOnAndBack() {
        try {
            ProviderTruthFlag.enabled = true
            assertTrue(ProviderTruthFlag.enabled)
        } finally {
            ProviderTruthFlag.enabled = ProviderTruthFlag.SHIPPED_DEFAULT
        }
        assertFalse(ProviderTruthFlag.enabled)
    }
}
