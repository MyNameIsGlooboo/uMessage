package com.glooboo.umessage.spam.engine

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.FilterDecision
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.UnknownSenderMode
import com.glooboo.umessage.testing.FakeTelephonyProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/** A number the user has sent a message to counts as known; one that only texted the user does not. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SentHistoryCheckerTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val normalizer = NumberNormalizer { "GB" }

    @Test fun numberTheUserSentToIsFound() {
        val sms = FakeTelephonyProvider.install()
        sms.addSms("0712 123 4567", "see you", 1_000, threadId = 1, type = Telephony.Sms.MESSAGE_TYPE_SENT)
        val checker = SystemContactChecker(app, normalizer)
        assertEquals(ContactLookup.FOUND, checker.sentTo("+447121234567"))
        assertEquals(ContactLookup.FOUND, checker.sentTo("07121234567"))
    }

    @Test fun outboxAndFailedCountAsSent() {
        val sms = FakeTelephonyProvider.install()
        sms.addSms("+447121111111", "a", 1_000, threadId = 1, type = Telephony.Sms.MESSAGE_TYPE_OUTBOX)
        sms.addSms("+447122222222", "b", 1_000, threadId = 2, type = Telephony.Sms.MESSAGE_TYPE_FAILED)
        val checker = SystemContactChecker(app, normalizer)
        assertEquals(ContactLookup.FOUND, checker.sentTo("+447121111111"))
        assertEquals(ContactLookup.FOUND, checker.sentTo("+447122222222"))
    }

    @Test fun numberThatOnlyTextedTheUserIsNotFound() {
        val sms = FakeTelephonyProvider.install()
        sms.addSms("+447121234567", "buy now", 1_000, threadId = 1, type = Telephony.Sms.MESSAGE_TYPE_INBOX)
        sms.addSms("+447350000000", "hi", 1_000, threadId = 2, type = Telephony.Sms.MESSAGE_TYPE_SENT)
        val checker = SystemContactChecker(app, normalizer)
        assertEquals(ContactLookup.NOT_FOUND, checker.sentTo("+447121234567"))
    }

    @Test fun differentNumberWithSameEndingIsNotFound() {
        val sms = FakeTelephonyProvider.install()
        sms.addSms("+447121234567", "hi", 1_000, threadId = 1, type = Telephony.Sms.MESSAGE_TYPE_SENT)
        val checker = SystemContactChecker(app, normalizer)
        assertEquals(ContactLookup.NOT_FOUND, checker.sentTo("+447351234567"))
    }

    @Test fun senderIdsAndBlankAreNotFound() {
        FakeTelephonyProvider.install()
        val checker = SystemContactChecker(app, normalizer)
        assertEquals(ContactLookup.NOT_FOUND, checker.sentTo("VM-HDFCBK"))
        assertEquals(ContactLookup.NOT_FOUND, checker.sentTo(""))
    }

    @Test fun missingSmsProviderGivesUnknown() {
        val checker = SystemContactChecker(app, normalizer)
        assertEquals(ContactLookup.UNKNOWN, checker.sentTo("+447121234567"))
    }

    class BrokenSmsProvider : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor =
            throw SecurityException("no READ_SMS")

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0
    }

    @Test fun providerErrorGivesUnknownAndTheFilterDelivers() {
        Robolectric.setupContentProvider(BrokenSmsProvider::class.java, "sms")
        val checker = SystemContactChecker(app, normalizer)
        assertEquals(ContactLookup.UNKNOWN, checker.sentTo("+447121234567"))

        val filter = DefaultSpamFilter(
            rules = { emptyList() },
            settings = { SpamSettings(mode = UnknownSenderMode.STRICT) },
            contacts = object : ContactChecker {
                override fun isContact(address: String) = false
                override fun sentTo(address: String) = checker.sentTo(address)
            },
            normalizer = normalizer,
        )
        assertEquals(
            FilterDecision.Allow(DecisionReason.CONTACTS_UNAVAILABLE),
            filter.evaluate(IncomingMessage("+447121234567", "buy now", 1)),
        )
    }

    @Test fun aNewSentMessageIsSeenAfterTheCacheIsDropped() {
        val sms = FakeTelephonyProvider.install()
        sms.addSms("+447350000000", "hi", 1_000, threadId = 1, type = Telephony.Sms.MESSAGE_TYPE_SENT)
        val checker = SystemContactChecker(app, normalizer)
        assertEquals(ContactLookup.NOT_FOUND, checker.sentTo("+447121234567"))
        // Inserting through the resolver notifies observers, as the real provider does.
        app.contentResolver.insert(Telephony.Sms.CONTENT_URI, ContentValues().apply {
            put("address", "+447121234567")
            put("type", Telephony.Sms.MESSAGE_TYPE_SENT)
        })
        app.contentResolver.notifyChange(Telephony.Sms.CONTENT_URI, null)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(ContactLookup.FOUND, checker.sentTo("+447121234567"))
    }
}
