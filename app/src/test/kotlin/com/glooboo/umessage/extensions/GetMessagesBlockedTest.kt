package com.glooboo.umessage.extensions

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.testing.FakeTelephonyProvider
import com.google.common.truth.Truth.assertThat
import org.fossify.commons.extensions.isNumberBlocked
import org.junit.Test
import org.junit.runner.RunWith

/** DL-20: blocking a number must not make the history already in its thread disappear. */
@RunWith(AndroidJUnit4::class)
class GetMessagesBlockedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun historyFromABlockedNumber_staysInTheThread() {
        val telephony = FakeTelephonyProvider.install()
        telephony.addSms("+15551234567", "Thanks for the loan", dateMs = 1_700_000_000_000, threadId = 7)
        telephony.addSms("+15551234567", "See you soon", dateMs = 1_700_000_100_000, threadId = 7)
        telephony.blockNumber("+15551234567")
        // The fake block list is really consulted, so this test would catch the old filter.
        assertThat(context.isNumberBlocked("+15551234567")).isTrue()

        val messages = context.getMessages(threadId = 7, includeScheduledMessages = false)

        assertThat(messages.map { it.body }).containsExactly("Thanks for the loan", "See you soon").inOrder()
    }
}
