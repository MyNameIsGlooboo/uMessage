package com.glooboo.umessage.spam.mms

import com.glooboo.umessage.spam.DecisionReason
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockedMmsPolicyTest {

    @Test fun smallSizeIsDownloadedButUnknownOrHugeIsNot() {
        assertFalse(BlockedMmsPolicy.sizeAllowed(0))
        assertFalse(BlockedMmsPolicy.sizeAllowed(-1))
        assertTrue(BlockedMmsPolicy.sizeAllowed(1))
        assertTrue(BlockedMmsPolicy.sizeAllowed(BlockedMmsPolicy.MAX_MESSAGE_BYTES))
        assertFalse(BlockedMmsPolicy.sizeAllowed(BlockedMmsPolicy.MAX_MESSAGE_BYTES + 1))
    }

    @Test fun roamingOrFullStorageSkipsTheDownload() {
        assertTrue(BlockedMmsPolicy.mayDownload(roaming = false, keptFiles = 0))
        assertFalse(BlockedMmsPolicy.mayDownload(roaming = true, keptFiles = 0))
        assertFalse(BlockedMmsPolicy.mayDownload(roaming = false, keptFiles = BlockedMmsPolicy.MAX_KEPT_FILES))
    }

    @Test fun explicitBlockRuleIsNeverDownloadedFrom() {
        assertFalse(BlockedMmsPolicy.reasonAllowed(DecisionReason.BLOCK_RULE))
        assertTrue(BlockedMmsPolicy.reasonAllowed(DecisionReason.UNKNOWN_SENDER))
        assertTrue(BlockedMmsPolicy.reasonAllowed(DecisionReason.FOREIGN_NUMBER))
    }
}
