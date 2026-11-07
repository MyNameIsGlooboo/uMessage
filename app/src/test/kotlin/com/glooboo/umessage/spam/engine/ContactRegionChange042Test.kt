package com.glooboo.umessage.spam.engine

import android.Manifest
import android.app.Application
import android.provider.ContactsContract
import android.telephony.TelephonyManager
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
 * F12: a contact saved without a country code ("07712 345678") got the country of the default region. When that
 * region changes (a foreign SIM, travel) the friend's texts, which carry "+44", must still be found.
 */
@RunWith(AndroidJUnit4::class)
class ContactRegionChange042Test {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
        Robolectric.setupContentProvider(FakeContactsProvider::class.java, ContactsContract.AUTHORITY)
        FakeContactsProvider.broken = false
        FakeContactsProvider.numbers = listOf("07712 345678")
    }

    private fun simCountry(iso: String) {
        shadowOf(app.getSystemService(TelephonyManager::class.java)).setSimCountryIso(iso)
    }

    @Test fun friendSavedNationallyIsFoundAfterTheDefaultRegionChanged() {
        simCountry("gb")
        val checker = SystemContactChecker(app, NumberNormalizer { "DE" })
        assertEquals(ContactLookup.FOUND, checker.lookup("+447712345678"))
        checker.close()
    }

    @Test fun aStrangerFromAnotherCountryStillIsNot() {
        simCountry("gb")
        val checker = SystemContactChecker(app, NumberNormalizer { "DE" })
        assertEquals(ContactLookup.NOT_FOUND, checker.lookup("+917712345678"))
        checker.close()
    }

    @Test fun plausibleRegionsNameTheSimCountry() {
        simCountry("gb")
        assertEquals(true, "GB" in plausibleRegions(app))
    }
}
