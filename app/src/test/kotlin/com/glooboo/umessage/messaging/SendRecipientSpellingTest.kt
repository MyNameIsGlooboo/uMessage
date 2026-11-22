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
 * once, whichever road the settings pick, also inside a real group. The SMS road is seen at the radio and in the
 * store. The MMS road is seen in the thread-id lookup the MMS library makes, on the sending thread, for each MMS it
 * stores: one lookup, with each person once. The numbers are made up.
 */
@RunWith(AndroidJUnit4::class)
class SendRecipientSpellingTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var store: FakeMessageStore

    @Before
    fun setUp() {
        ShadowRecordingSmsManager.reset()
        store = FakeMessageStore.install()
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

    /** The recipients of each MMS the library stored from this test's thread (the shortcut report asks elsewhere). */
    private fun mmsRecipients(): List<Set<String>> {
        val sender = Thread.currentThread().name
        return store.threadLookups.filter { it.askedOn == sender }.map { it.recipients.toSet() }
    }

    /** No SMS went out or was stored; exactly one MMS was stored, to [recipients]. */
    private fun assertOneMmsTo(vararg recipients: String) {
        assertThat(ShadowRecordingSmsManager.textSends).isEmpty()
        assertThat(smsRows()).isEqualTo(0)
        assertThat(mmsRecipients()).containsExactly(recipients.toSet())
    }

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
        assertThat(smsRows()).isEqualTo(1)
    }

    @Test
    fun oneNumberInTwoSpellingsStaysOneSmsWithGroupMmsOn() {
        // Not a group: the duplicate goes before the choice, so no group MMS is made of one person.
        app.config.sendGroupMessageMMS = true

        send("Hello", listOf(LOCAL_SPELLING, NUMBER))

        assertThat(destinations()).containsExactly(LOCAL_SPELLING)
        assertThat(smsRows()).isEqualTo(1)
    }

    @Test
    fun aLongTextToOneNumberInTwoSpellingsStaysOneSmsWhenMmsWasNeverChosen() {
        send("a".repeat(2_000), listOf(NUMBER, LOCAL_SPELLING))

        assertThat(ShadowRecordingSmsManager.textSends).hasSize(1)
        assertThat(destinations()).containsExactly(NUMBER)
        assertThat(smsRows()).isEqualTo(1)
    }

    @Test
    fun aGroupWithOnePersonTwiceIsTwoSmsWithGroupMmsOff() {
        send("Hello", listOf(NUMBER, OTHER, LOCAL_SPELLING))

        assertThat(destinations()).containsExactly(NUMBER, OTHER)
        // One row for each of the two, and the group's summary row.
        assertThat(smsRows()).isEqualTo(3)
    }

    @Test
    fun aGroupWithOnePersonTwiceIsOneGroupMmsOfTwoWithGroupMmsOn() {
        app.config.sendGroupMessageMMS = true

        send("Hello", listOf(NUMBER, OTHER, LOCAL_SPELLING))

        assertOneMmsTo(NUMBER, OTHER)
    }

    @Test
    fun aLongTextToOneNumberInTwoSpellingsIsOneMmsFromTheChosenParts() {
        app.config.sendLongAsMmsAfterParts = 2

        send("a".repeat(161), listOf(NUMBER, LOCAL_SPELLING))

        assertOneMmsTo(NUMBER)
    }

    @Test
    fun aPictureIsOneMmsNotAnSms() {
        send("Look", listOf(NUMBER), attachments = listOf(picture()))

        assertOneMmsTo(NUMBER)
    }

    @Test
    fun aPictureToOneNumberInTwoSpellingsIsOneMmsToOnePerson() {
        send("", listOf(NUMBER, LOCAL_SPELLING), attachments = listOf(picture()))

        assertOneMmsTo(NUMBER)
    }

    private companion object {
        const val NUMBER = "+15550001234"
        const val LOCAL_SPELLING = "5550001234"
        const val OTHER = "+15550001235"
    }
}
