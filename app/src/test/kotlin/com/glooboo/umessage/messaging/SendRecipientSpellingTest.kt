package com.glooboo.umessage.messaging

import android.app.Application
import android.net.Uri
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.models.Attachment
import com.glooboo.umessage.testing.FakeMessageStore
import com.glooboo.umessage.testing.ShadowRecordingSmsManager
import com.glooboo.umessage.testing.joinAppBackgroundThreads
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * M-T5, the SMS-or-MMS choice of [sendMessageDetailed] beyond SendDispatchTest: one number written two ways goes out
 * once, whichever road the settings pick, and a picture never goes out as an SMS. The number is made up.
 */
@RunWith(AndroidJUnit4::class)
class SendRecipientSpellingTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        ShadowRecordingSmsManager.reset()
        FakeMessageStore.install()
        app.config.sendGroupMessageMMS = false
        app.config.sendLongAsMmsAfterParts = LongTextMms.NEVER
    }

    @After
    fun tearDown() {
        joinAppBackgroundThreads()
        resetSharedMessagesDatabase()
    }

    private fun send(text: String, addresses: List<String>, attachments: List<Attachment> = emptyList()) =
        app.sendMessageDetailed(text, addresses, subId = null, attachments = attachments)

    private fun smsRows(): Int =
        app.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID), null, null, null)
            .use { it?.count ?: 0 }

    private fun destinations() = ShadowRecordingSmsManager.textSends.map { it.destination }

    private fun picture(): Attachment {
        val file = File(app.cacheDir, "picture.jpg").apply { writeBytes(ByteArray(16) { it.toByte() }) }
        return Attachment(
            id = null, messageId = 1L, uriString = Uri.fromFile(file).toString(), mimetype = "image/jpeg",
            width = 0, height = 0, filename = "picture.jpg",
        )
    }

    @Test
    fun oneNumberInTwoSpellingsGetsOneSms() {
        send("Hello", listOf(NUMBER, LOCAL_SPELLING))

        assertThat(destinations()).containsExactly(NUMBER)
    }

    @Test
    fun oneNumberInTwoSpellingsStaysOneSmsWithGroupMmsOn() {
        // Not a group: the duplicate goes before the choice, so no group MMS is made of one person.
        app.config.sendGroupMessageMMS = true

        send("Hello", listOf(LOCAL_SPELLING, NUMBER))

        assertThat(destinations()).containsExactly(LOCAL_SPELLING)
    }

    @Test
    fun aLongTextToOneNumberInTwoSpellingsStaysOneSmsWhenMmsWasNeverChosen() {
        send("a".repeat(2_000), listOf(NUMBER, LOCAL_SPELLING))

        assertThat(ShadowRecordingSmsManager.textSends).hasSize(1)
        assertThat(destinations()).containsExactly(NUMBER)
    }

    @Test
    fun aLongTextToOneNumberInTwoSpellingsIsNotSentAsSmsFromTheChosenParts() {
        app.config.sendLongAsMmsAfterParts = 2

        send("a".repeat(161), listOf(NUMBER, LOCAL_SPELLING))

        assertThat(ShadowRecordingSmsManager.textSends).isEmpty()
        assertThat(smsRows()).isEqualTo(0)
    }

    @Test
    fun aPictureIsNeverSentAsSms() {
        send("Look", listOf(NUMBER), attachments = listOf(picture()))

        assertThat(ShadowRecordingSmsManager.textSends).isEmpty()
        assertThat(smsRows()).isEqualTo(0)
    }

    @Test
    fun aPictureToOneNumberInTwoSpellingsIsNeverSentAsSms() {
        send("", listOf(NUMBER, LOCAL_SPELLING), attachments = listOf(picture()))

        assertThat(ShadowRecordingSmsManager.textSends).isEmpty()
        assertThat(smsRows()).isEqualTo(0)
    }

    private companion object {
        const val NUMBER = "+15550001234"
        const val LOCAL_SPELLING = "5550001234"
    }
}
