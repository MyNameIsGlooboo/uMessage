package com.glooboo.umessage.ui.thread

import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.data.SpamPlacement
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class DecisionReasonLabelTest {
    @Test
    fun everyReasonTheEngineLogsHasALabel() {
        for (reason in DecisionReason.entries) {
            assertWithMessage("no label for $reason").that(MessageDecisions.reasonLabel(reason.name)).isNotNull()
        }
    }

    @Test
    fun everyPlacementResultThatReachesTheLogHasALabel() {
        for (result in SpamPlacement.Result.entries.filter { it != SpamPlacement.Result.PLACED }) {
            assertWithMessage("no label for $result").that(MessageDecisions.reasonLabel(result.name)).isNotNull()
        }
    }
}
