package com.glooboo.umessage.spam.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** I08: the weekly summary counts the week's Spam texts, and how many of them look like codes or bank alerts. */
@RunWith(AndroidJUnit4::class)
class WeeklyDigestTest {

    private val now = 50 * WeeklyDigest.WEEK_MS
    private val day = WeeklyDigest.WEEK_MS / 7

    private fun held(body: String, address: String = "+15550001234", ageDays: Long = 1) =
        WeeklyDigest.SpamText(address = address, body = body, receivedAt = now - ageDays * day)

    @Test
    fun nothingHeldGivesAnEmptyDigest() {
        assertThat(WeeklyDigest.computeTexts(emptyList(), now)).isEqualTo(WeeklyDigest(0, 0))
    }

    @Test
    fun onlyTheLastWeekCounts() {
        val digest = WeeklyDigest.computeTexts(
            listOf(held("Win a free trip", ageDays = 2), held("Cheap loans", ageDays = 6), held("Old ad", ageDays = 9)),
            now,
        )

        assertThat(digest).isEqualTo(WeeklyDigest(2, 0))
    }

    @Test
    fun codesAndBankAlertsAreCountedAsImportant() {
        val digest = WeeklyDigest.computeTexts(
            listOf(
                held("Your verification code is 482913. Do not share it."),
                held("Deposited 10 USD", address = "BankName"),
                held("Buy one get one free at our new store"),
            ),
            now,
        )

        assertThat(digest).isEqualTo(WeeklyDigest(3, 2))
    }

    @Test
    fun aMessageDatedInTheFutureIsNotCounted() {
        val future = held("Hello").copy(receivedAt = now + day)

        assertThat(WeeklyDigest.computeTexts(listOf(future), now).held).isEqualTo(0)
    }
}
