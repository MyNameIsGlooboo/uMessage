package com.glooboo.umessage.spam.mms

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockedMmsPolicyTest {

    @Test fun smallOrUnknownSizeIsDownloadedButHugeIsNot() {
        assertTrue(BlockedMmsPolicy.sizeAllowed(0))
        assertTrue(BlockedMmsPolicy.sizeAllowed(BlockedMmsPolicy.MAX_MESSAGE_BYTES))
        assertFalse(BlockedMmsPolicy.sizeAllowed(BlockedMmsPolicy.MAX_MESSAGE_BYTES + 1))
    }

    @Test fun roamingOrFullStorageSkipsTheDownload() {
        assertTrue(BlockedMmsPolicy.mayDownload(roaming = false, keptFiles = 0))
        assertFalse(BlockedMmsPolicy.mayDownload(roaming = true, keptFiles = 0))
        assertFalse(BlockedMmsPolicy.mayDownload(roaming = false, keptFiles = BlockedMmsPolicy.MAX_KEPT_FILES))
    }
}
