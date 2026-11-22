package com.glooboo.umessage.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InboxCardPolicyTest {

    @Test
    fun checklistBeatsTheCodeQuestion() {
        assertEquals(InboxCard.Checklist, InboxCardPolicy.pick(checklistDue = true, cleanupDue = true))
    }

    @Test
    fun codeQuestionShowsOnlyWhenNothingElseIsDue() {
        assertEquals(InboxCard.CodeCleanup, InboxCardPolicy.pick(false, cleanupDue = true))
    }

    @Test
    fun noCardWhenNothingIsDue() {
        assertNull(InboxCardPolicy.pick(false, false))
    }
}
