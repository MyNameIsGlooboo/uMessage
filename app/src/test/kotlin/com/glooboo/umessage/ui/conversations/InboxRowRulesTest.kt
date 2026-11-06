package com.glooboo.umessage.ui.conversations

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InboxRowRulesTest {

    private fun row(id: Long, code: String? = null) = ConversationRow(
        threadId = id, title = "Sender $id", snippet = "", dateLabel = "", unread = code != null, unreadBadge = "",
        unreadCount = 0, pinned = false, muted = false, hasDraft = false, scheduled = false, simSlot = null,
        photoUri = "", isGroup = false, phoneNumber = "", otpCode = code,
    )

    @Test
    fun onlyTheFirstFreshCodeGetsTheChip() {
        val rows = listOf(row(1), row(2, "111111"), row(3, "222222"))
        assertEquals(2L, newestCodeThread(rows))
    }

    @Test
    fun noChipWithoutAFreshCode() {
        assertNull(newestCodeThread(listOf(row(1), row(2))))
        assertNull(newestCodeThread(emptyList()))
    }

    @Test
    fun bareNumbersGetDifferentTonesButEachKeepsItsOwn() {
        val numbers = listOf("+15550101", "+15550102", "+15550103", "+15550104", "+15550105", "+15550106")
        val tones = numbers.map { avatarToneIndex(it, 8) }
        assertTrue(tones.toSet().size > 1)
        assertEquals(tones, numbers.map { avatarToneIndex(it, 8) })
    }
}
