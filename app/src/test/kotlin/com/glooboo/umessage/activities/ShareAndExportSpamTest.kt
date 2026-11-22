package com.glooboo.umessage.activities

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.dialogs.pickerConversations
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.spam.SpamThread
import com.glooboo.umessage.spam.engine.SpamCause
import com.glooboo.umessage.spam.engine.NumberNormalizer
import com.glooboo.umessage.spam.engine.NumberReaders
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Provider truth, step H (section 5, U9): the new-conversation suggestions and the export picker do not offer a chat
 * in Spam. With the flag off they offer everything, as before. Fake numbers only.
 */
@RunWith(AndroidJUnit4::class)
class ShareAndExportSpamTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = offMainThread {
        listOf(SPAM to SPAM_SENDER, SHOWN to FRIEND).forEach { (thread, number) ->
            app.conversationsDB.insertOrUpdate(chat(thread, number))
        }
        app.conversationsDB.insertSpamThread(SpamThread(SPAM, SPAM_SENDER, SpamCause.BLOCKED, spamSince = 1L))
        Unit
    }

    @After
    fun tearDown() = resetSharedMessagesDatabase()

    private fun chat(thread: Long, number: String) = Conversation(thread, "", 0, true, number, "", false, number)

    private fun contact(id: Int, number: String) = SimpleContact(
        rawId = id, contactId = id, name = "Contact $id", photoUri = "",
        phoneNumbers = arrayListOf(PhoneNumber(number, 0, "", number)),
        birthdays = ArrayList(), anniversaries = ArrayList(),
    )

    @Test
    fun aSuggestedSenderWhoseChatIsInSpamIsNotOffered() {
        // The Spam sender written another way is still the same person.
        val suggested = listOf(contact(1, "(555) 000-1234"), contact(2, FRIEND))

        val shown = offMainThread { app.withoutSpamSenders(suggested) }

        assertThat(shown.map { it.contactId }).containsExactly(2)
    }

    @Test
    fun aNumberWithNoKnownCountryStillMatchesItsSpamSender() {
        // H-17: with no region known, "5550001234" is the national part of "+15550001234": no shared key, but the
        // same sender; the plain cases are one set lookup.
        val isSpam = spamSenderTest(listOf(SPAM_SENDER), NumberReaders(NumberNormalizer { "" }))

        assertThat(isSpam("5550001234")).isTrue()
        assertThat(isSpam(SPAM_SENDER)).isTrue()
        assertThat(isSpam(FRIEND)).isFalse()
        assertThat(isSpam("5550001299")).isFalse()
    }

    @Test
    fun theExportPickerLeavesOutTheSpamChat() {
        val listed = offMainThread { pickerConversations(app) { listOf(chat(SPAM, SPAM_SENDER), chat(SHOWN, FRIEND)) } }

        assertThat(listed.map { it.threadId }).containsExactly(SHOWN)
    }

    private companion object {
        const val SPAM = 101L
        const val SHOWN = 102L
        const val SPAM_SENDER = "+15550001234"
        const val FRIEND = "+15550001299"
    }
}
