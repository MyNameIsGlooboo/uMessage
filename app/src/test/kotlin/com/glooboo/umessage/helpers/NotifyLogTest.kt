package com.glooboo.umessage.helpers

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** N5: the uMessageNotify line names the channel, a hash of the chat and the result, and nothing else. */
class NotifyLogTest {

    @Test
    fun lineHasChannelHashAndResult() {
        val line = NotifyLog.line("verification_codes", 42L, NotifyResult.POSTED, null)

        assertThat(line).matches("post ch=verification_codes t=[0-9a-f]{8} r=POSTED")
    }

    @Test
    fun threadIdIsNeverWrittenRaw() {
        val line = NotifyLog.line(NOTIFICATION_CHANNEL_ID, 987654321L, NotifyResult.FALLBACK, null)
        val again = NotifyLog.line(NOTIFICATION_CHANNEL_ID, 987654321L, NotifyResult.FALLBACK, null)

        assertThat(line).doesNotContain("987654321")
        val hash = Regex("t=([^ ]+)").find(line)!!.groupValues[1]
        assertThat(hash).matches("[0-9a-f]{8}")
        assertThat(again).isEqualTo(line)
    }

    @Test
    fun perChatChannelIsWrittenAsChat() {
        assertThat(NotifyLog.line("987654321", 987654321L, NotifyResult.POSTED, null)).contains("ch=chat ")
        assertThat(NotifyLog.line(null, 1L, NotifyResult.SKIPPED_READ, null)).contains("ch=- ")
    }

    @Test
    fun causeIsClassNameOnly() {
        val line = NotifyLog.line(null, 1L, NotifyResult.FAILED, IllegalStateException("secret 123456"))

        assertThat(line).endsWith(" e=IllegalStateException")
        assertThat(line).doesNotContain("secret")
        assertThat(line).doesNotContain("123456")
    }
}
