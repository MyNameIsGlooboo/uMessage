package com.glooboo.umessage.spam.engine

import android.Manifest
import android.app.Application
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.engine.SystemContactCheckerTest.FakeContactsProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/**
 * DL-24: the spam filter's contact check. The fake provider's PhoneLookup matches on the last 9 digits, as Android's
 * does, so a stranger from another country used to be taken for a contact.
 */
@RunWith(AndroidJUnit4::class)
class ContactLookupCountryTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
        Robolectric.setupContentProvider(FakeContactsProvider::class.java, ContactsContract.AUTHORITY)
        FakeContactsProvider.broken = false
        FakeContactsProvider.numbers = listOf("+44 712 345 6789")
    }

    @Test fun strangerWithTheSameLastDigitsIsNotAContact() {
        val checker = SystemContactChecker(app, NumberNormalizer { "GB" })
        for (stranger in listOf("+91 712 345 6789", "+17123456789", "00917123456789")) {
            assertEquals(stranger, ContactLookup.NOT_FOUND, checker.lookup(stranger))
        }
    }

    @Test fun theContactIsFoundInEveryForm() {
        val checker = SystemContactChecker(app, NumberNormalizer { "GB" })
        for (form in listOf("+447123456789", "07123456789", "00447123456789", "۰۷۱۲۳۴۵۶۷۸۹")) {
            assertEquals(form, ContactLookup.FOUND, checker.lookup(form))
        }
    }

    @Test fun contactSavedNationallyMatchesOnlyTheHomeNumber() {
        FakeContactsProvider.numbers = listOf("0712 345 6789")
        val checker = SystemContactChecker(app, NumberNormalizer { "GB" })
        assertEquals(ContactLookup.FOUND, checker.lookup("+447123456789"))
        assertEquals(ContactLookup.NOT_FOUND, checker.lookup("+917123456789"))
    }
}
