package com.glooboo.umessage.ui.main

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecentCodesTest {
    private val now = 1_800_000_000_000L
    private val hour = 60L * 60 * 1000

    private fun sms(thread: Long, body: String, ago: Long, address: String = "5550$thread") =
        ReceivedSms(threadId = thread, address = address, body = body, date = now - ago)

    /** Test extractor: the first 4-8 digit run is the code. */
    private val digits: (String) -> String? = { Regex("""\d{4,8}""").find(it)?.value }

    @Test
    fun codesOfTheLastDayAreListedReadOrUnreadNewestFirst() {
        val found = RecentCodes.pick(
            listOf(sms(1, "code 1111", ago = 20 * hour), sms(2, "code 2222", ago = 1 * hour)),
            now,
            digits,
        )
        assertThat(found.map { it.code }).containsExactly("2222", "1111").inOrder()
    }

    @Test
    fun olderThanADayIsLeftOut() {
        val found = RecentCodes.pick(listOf(sms(1, "code 1111", ago = 25 * hour)), now, digits)
        assertThat(found).isEmpty()
    }

    @Test
    fun messagesWithoutACodeAndRepeatsAreSkipped() {
        val found = RecentCodes.pick(
            listOf(sms(1, "hello", ago = 1), sms(2, "code 3333", ago = 2), sms(3, "again 3333", ago = 3)),
            now,
            digits,
        )
        assertThat(found.map { it.threadId }).containsExactly(2L)
    }

    @Test
    fun atMostFiveCodes() {
        val messages = (1..9).map { sms(it.toLong(), "code 100$it", ago = it.toLong()) }
        assertThat(RecentCodes.pick(messages, now, digits)).hasSize(RecentCodes.MAX_CODES)
    }

    @Test
    fun realExtractorFindsAPersianCode() {
        val found = RecentCodes.pick(listOf(sms(1, "کد تایید شما ۱۲۳۴۵۶ است", ago = hour)), now)
        assertThat(found).hasSize(1)
    }

    @Test
    fun chipsAreNamedByConversationTitleOrAddress() {
        val found = listOf(FoundCode(1, "5551", "1111", now), FoundCode(2, "5552", "2222", now))
        val chips = RecentCodes.chips(found, titles = mapOf(1L to "Bank"), fallback = emptyList())
        assertThat(chips).containsExactly(RecentCode("Bank", "1111"), RecentCode("5552", "2222")).inOrder()
    }

    @Test
    fun rowCodesFillInWithoutDuplicates() {
        val found = listOf(FoundCode(1, "5551", "1111", now))
        val fallback = listOf(RecentCode("Bank", "1111"), RecentCode("Shop", "4444"))
        val chips = RecentCodes.chips(found, emptyMap(), fallback = fallback)
        assertThat(chips.map { it.code }).containsExactly("1111", "4444").inOrder()
    }
}
