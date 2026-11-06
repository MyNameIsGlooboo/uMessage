package com.glooboo.umessage.messaging

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.messaging.SentParts.Outcome
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SentPartsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val uri = Uri.parse("content://sms/41")

    /** Plays [reports] (true = part sent) through the reducer and returns each outcome. */
    private fun play(partCount: Int, vararg reports: Boolean): List<Outcome> {
        var progress = SentParts.Progress()
        return reports.map { sent ->
            val (next, outcome) = SentParts.reduce(progress, sent, partCount)
            progress = next
            outcome
        }
    }

    @Test
    fun failedFirstPartThenSentPartsStaysFailed() {
        // RM-04: part 1 failed, parts 2 and 3 sent. The friend got an incomplete message, so it must not say sent.
        assertThat(play(3, false, true, true))
            .containsExactly(Outcome.FAILED_FIRST, Outcome.FAILED_AGAIN, Outcome.FAILED_AGAIN).inOrder()
    }

    @Test
    fun sentOnlyWhenEveryPartIsSent() {
        assertThat(play(3, true, true, true))
            .containsExactly(Outcome.STILL_SENDING, Outcome.STILL_SENDING, Outcome.SENT).inOrder()
    }

    @Test
    fun lastPartFailingFailsTheMessage() {
        assertThat(play(2, true, false)).containsExactly(Outcome.STILL_SENDING, Outcome.FAILED_FIRST).inOrder()
    }

    @Test
    fun severalFailedPartsTellTheUserOnce() {
        assertThat(play(3, false, false, false).count { it == Outcome.FAILED_FIRST }).isEqualTo(1)
    }

    @Test
    fun singlePartMessageIsDecidedByItsOnlyReport() {
        assertThat(SentParts.record(context, uri, partSent = true, partCount = 1)).isEqualTo(Outcome.SENT)
        assertThat(SentParts.record(context, uri, partSent = false, partCount = 1)).isEqualTo(Outcome.FAILED_FIRST)
    }

    @Test
    fun recordKeepsProgressAcrossReports() {
        SentParts.start(context, uri)
        assertThat(SentParts.record(context, uri, partSent = false, partCount = 2)).isEqualTo(Outcome.FAILED_FIRST)
        assertThat(SentParts.record(context, uri, partSent = true, partCount = 2)).isEqualTo(Outcome.FAILED_AGAIN)
    }

    @Test
    fun resendStartsFromAClearSlate() {
        SentParts.start(context, uri)
        SentParts.record(context, uri, partSent = false, partCount = 2)

        // The user taps resend: the same row (same uri) goes out again and this time every part is sent.
        SentParts.start(context, uri)
        assertThat(SentParts.record(context, uri, partSent = true, partCount = 2)).isEqualTo(Outcome.STILL_SENDING)
        assertThat(SentParts.record(context, uri, partSent = true, partCount = 2)).isEqualTo(Outcome.SENT)
    }
}
