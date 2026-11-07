package com.glooboo.umessage.spam.engine

import android.app.Application
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** SP3: every incoming text used to build a new contact checker, each leaving content observers behind. */
@RunWith(AndroidJUnit4::class)
class SharedContactChecker042Test {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun observers(uri: android.net.Uri) = shadowOf(app.contentResolver).getContentObservers(uri).size

    @Before fun setUp() = SpamEngine.resetContactChecker()

    @After fun tearDown() = SpamEngine.resetContactChecker()

    @Test fun everyCallGetsTheSameChecker() {
        assertThat(SpamEngine.contactChecker(app)).isSameInstanceAs(SpamEngine.contactChecker(app))
    }

    @Test fun checkingTheSentHistoryManyTimesRegistersObserversOnce() {
        SpamEngine.hasWrittenTo(app, "+15550100")
        val sms = observers(Telephony.Sms.CONTENT_URI)
        val contacts = observers(ContactsContract.Contacts.CONTENT_URI)
        assertThat(sms).isEqualTo(1)
        assertThat(contacts).isEqualTo(1)

        repeat(10) { SpamEngine.hasWrittenTo(app, "+1555010$it") }

        assertThat(observers(Telephony.Sms.CONTENT_URI)).isEqualTo(sms)
        assertThat(observers(ContactsContract.Contacts.CONTENT_URI)).isEqualTo(contacts)
    }

    @Test fun closingTheCheckerUnregistersItsObservers() {
        SpamEngine.contactChecker(app)
        assertThat(observers(Telephony.Sms.CONTENT_URI)).isEqualTo(1)

        SpamEngine.resetContactChecker()

        assertThat(observers(Telephony.Sms.CONTENT_URI)).isEqualTo(0)
        assertThat(observers(ContactsContract.Contacts.CONTENT_URI)).isEqualTo(0)
    }
}
