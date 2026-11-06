package com.glooboo.umessage.spam.engine

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/** T4 / SE-18: the real contact checker says UNKNOWN, never NOT_FOUND, when the contacts provider fails. */
@RunWith(AndroidJUnit4::class)
class SystemContactCheckerTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val normalizer = NumberNormalizer { "GB" }

    /** Answers both the bulk number query and PhoneLookup from [numbers]; throws everything when [broken]. */
    class FakeContactsProvider : ContentProvider() {
        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor {
            if (broken) error("contacts database busy")
            val cursor = MatrixCursor(arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER))
            val lookup = uri.pathSegments.firstOrNull() == "phone_lookup"
            val wanted = if (lookup) uri.lastPathSegment.orEmpty().filter(Char::isDigit).takeLast(9) else null
            numbers.filter { wanted == null || it.filter(Char::isDigit).endsWith(wanted) }.forEach { cursor.addRow(arrayOf(it)) }
            return cursor
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0

        companion object {
            @Volatile var numbers: List<String> = emptyList()
            @Volatile var broken = false
        }
    }

    @Before fun grantContacts() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
        FakeContactsProvider.numbers = emptyList()
        FakeContactsProvider.broken = false
    }

    @Test fun providerMissingGivesUnknown() {
        val checker = SystemContactChecker(app, normalizer)
        assertEquals(ContactLookup.UNKNOWN, checker.lookup("+447121234567"))
    }

    @Test fun providerErrorGivesUnknown() {
        Robolectric.setupContentProvider(FakeContactsProvider::class.java, ContactsContract.AUTHORITY)
        FakeContactsProvider.broken = true
        val checker = SystemContactChecker(app, normalizer)
        assertEquals(ContactLookup.UNKNOWN, checker.lookup("+447121234567"))
    }

    @Test fun savedNumberIsFound() {
        Robolectric.setupContentProvider(FakeContactsProvider::class.java, ContactsContract.AUTHORITY)
        FakeContactsProvider.numbers = listOf("0712 123 4567")
        val checker = SystemContactChecker(app, normalizer)
        assertEquals(ContactLookup.FOUND, checker.lookup("+447121234567"))
    }

    @Test fun strangerIsNotFoundWhenTheProviderAnswers() {
        Robolectric.setupContentProvider(FakeContactsProvider::class.java, ContactsContract.AUTHORITY)
        FakeContactsProvider.numbers = listOf("0712 123 4567")
        val checker = SystemContactChecker(app, normalizer)
        assertEquals(ContactLookup.NOT_FOUND, checker.lookup("+447350000000"))
    }

    @Test fun senderIdsAreNeverContacts() {
        val checker = SystemContactChecker(app, normalizer)
        assertEquals(ContactLookup.NOT_FOUND, checker.lookup("VM-HDFCBK"))
    }
}
