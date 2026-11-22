package com.glooboo.umessage.ui.main

import com.glooboo.umessage.spam.FirstRunState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Provider truth, step C (U13, answer 15): "N chats are now in Spam" has the top priority of the one inbox card,
 * counts every moved chat and stays until tapped (the runner's cardPending).
 */
class InboxCardMovedTest {

    @Test
    fun theMovedCardComesBeforeEveryOtherCard() {
        val card = InboxCardPolicy.pick(checklistDue = true, cleanupDue = true, spamMovedDue = true)
        assertEquals(InboxCard.SpamMoved, card)
    }

    @Test
    fun withoutItTheOldOrderStays() {
        assertEquals(InboxCard.Checklist, InboxCardPolicy.pick(true, true, spamMovedDue = false))
        assertEquals(InboxCard.CodeCleanup, InboxCardPolicy.pick(false, true))
    }

    @Test
    fun theCardCountsTheMovedChatsUntilItIsTapped() {
        assertEquals(7, spamMovedForCard(FirstRunState(cardPending = true, movedToSpam = 7)))
        // Tapped (acknowledged): the runner clears cardPending, and the card is gone until the next first sort.
        assertEquals(0, spamMovedForCard(FirstRunState(cardPending = false, movedToSpam = 7)))
        assertEquals(0, spamMovedForCard(FirstRunState(cardPending = true, movedToSpam = 0)))
    }
}
