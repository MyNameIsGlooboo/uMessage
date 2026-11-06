package com.glooboo.umessage.ui.main

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InboxCardPolicyTest {

    @Test
    fun heldSpamComesFirstWhateverElseIsDue() {
        val card = InboxCardPolicy.pick(spamHeldDue = true, checklistDue = true, cleanupDue = true)
        assertEquals(InboxCard.SpamHeld, card)
    }

    @Test
    fun checklistBeatsTheCodeQuestion() {
        assertEquals(InboxCard.Checklist, InboxCardPolicy.pick(false, checklistDue = true, cleanupDue = true))
    }

    @Test
    fun codeQuestionShowsOnlyWhenNothingElseIsDue() {
        assertEquals(InboxCard.CodeCleanup, InboxCardPolicy.pick(false, false, cleanupDue = true))
    }

    @Test
    fun noCardWhenNothingIsDue() {
        assertNull(InboxCardPolicy.pick(false, false, false))
    }
}
