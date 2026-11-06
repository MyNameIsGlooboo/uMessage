package com.glooboo.umessage.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimSelectionTest {

    private val active = listOf(3, 4)

    private fun pick(saved: Int? = null, last: Int? = null, app: Int = DEFAULT_SIM_PHONE, system: Int = -1) =
        pickSendSubscriptionId(active, saved, last, app, system)

    // The saved value is a subscription id: subId 4 must pick subId 4, never list index 4 (or 1).
    @Test fun savedIdIsSubscriptionIdNotIndex() = assertEquals(4, pick(saved = 4))

    @Test fun savedIdWinsOverEverything() = assertEquals(3, pick(saved = 3, last = 4, app = 4, system = 4))

    @Test fun inactiveSavedFallsBackToLastReceived() = assertEquals(4, pick(saved = 1, last = 4, app = 3))

    @Test fun lastReceivedBeforeAppDefault() = assertEquals(4, pick(last = 4, app = 3))

    @Test fun appDefaultBeforeSystem() = assertEquals(3, pick(app = 3, system = 4))

    @Test fun phoneOrAskAppDefaultUsesSystem() {
        assertEquals(4, pick(app = DEFAULT_SIM_PHONE, system = 4))
        assertEquals(4, pick(app = DEFAULT_SIM_ASK, system = 4))
    }

    @Test fun nothingActiveGivesNull() = assertNull(pick(saved = 1, last = 2, app = 5, system = 6))
}
