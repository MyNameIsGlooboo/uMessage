package com.glooboo.umessage.helpers

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.invalidateSimpleContactsCache
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper

/** CM-15: the address book loads by raw-id lookups, keeps its old results, and is kept until the contacts change. */
@RunWith(AndroidJUnit4::class)
class AvailableContactsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    data class Person(val rawId: Int, val given: String?, val numbers: List<String>, val birthday: String? = null)

    /** Answers the name, phone and event queries SimpleContactsHelper makes, from [people]. */
    class FakeAddressBook : ContentProvider() {
        override fun onCreate() = true

        override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor {
            queries++
            val columns = projection!!
            val cursor = MatrixCursor(columns)
            fun row(values: Map<String, Any?>) = cursor.addRow(columns.map { values[it] }.toTypedArray())
            val isPhones = uri.pathSegments.lastOrNull() == "phones"
            when {
                isPhones -> people.forEach { p ->
                    p.numbers.forEach { n ->
                        row(mapOf("raw_contact_id" to p.rawId, "contact_id" to p.rawId, "data4" to n, "data1" to n, "data2" to 2))
                    }
                }

                args?.firstOrNull() == Event.CONTENT_ITEM_TYPE -> if (args[1] == Event.TYPE_BIRTHDAY.toString()) {
                    people.filter { it.birthday != null }.forEach { row(mapOf("raw_contact_id" to it.rawId, "data1" to it.birthday)) }
                }

                else -> people.filter { it.given != null }.forEach {
                    row(mapOf("raw_contact_id" to it.rawId, "contact_id" to it.rawId, "data2" to it.given, "mimetype" to StructuredName.CONTENT_ITEM_TYPE))
                }
            }
            return cursor
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0

        companion object {
            @Volatile var people: List<Person> = emptyList()
            @Volatile var queries = 0
        }
    }

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
        Robolectric.setupContentProvider(FakeAddressBook::class.java, ContactsContract.AUTHORITY)
        FakeAddressBook.queries = 0
        invalidateSimpleContactsCache()
    }

    @Test
    fun mergesNamesNumbersAndBirthdaysAndDropsDuplicates() {
        FakeAddressBook.people = listOf(
            Person(1, "Ali", listOf("+447121111111", "+447122222222")),
            Person(2, "Ali", listOf("+447122222222")), // same name, numbers all on contact 1: dropped
            Person(3, "Sara", listOf("+447123333333"), birthday = "1990-05-01"),
            Person(4, null, listOf("+447124444444")), // no name: dropped
        )

        val contacts = SimpleContactsHelper(app).getAvailableContactsSync(false)

        assertThat(contacts.map { it.rawId to it.name }).containsExactly(1 to "Ali", 3 to "Sara").inOrder()
        assertThat(contacts[0].phoneNumbers.map { it.normalizedNumber }).containsExactly("+447121111111", "+447122222222").inOrder()
        assertThat(contacts[1].birthdays).containsExactly("1990-05-01")
    }

    @Test
    fun aSecondLoadComesFromMemoryUntilTheAddressBookChanges() {
        FakeAddressBook.people = listOf(Person(1, "Ali", listOf("+447121111111")))
        val helper = SimpleContactsHelper(app)
        helper.getAvailableContactsSync(false)
        val afterFirst = FakeAddressBook.queries

        val second = helper.getAvailableContactsSync(false)
        second[0].name = "changed by a caller"

        assertThat(FakeAddressBook.queries).isEqualTo(afterFirst)
        assertThat(helper.getAvailableContactsSync(false)[0].name).isEqualTo("Ali")

        FakeAddressBook.people = listOf(Person(1, "Ali Rezaei", listOf("+447121111111")))
        app.contentResolver.notifyChange(ContactsContract.Contacts.CONTENT_URI, null)
        ShadowLooper.idleMainLooper()

        assertThat(helper.getAvailableContactsSync(false).single().name).isEqualTo("Ali Rezaei")
        assertThat(FakeAddressBook.queries).isGreaterThan(afterFirst)
    }

    @Test
    fun aLargeAddressBookLoadsQuickly() {
        FakeAddressBook.people = (1..5000).map { Person(it, "Person $it", listOf("+44712%07d".format(it))) }

        val start = System.nanoTime()
        val contacts = SimpleContactsHelper(app).getAvailableContactsSync(false)
        val millis = (System.nanoTime() - start) / 1_000_000

        assertThat(contacts).hasSize(5000)
        assertThat(millis).isLessThan(5_000L) // includes class loading on first use; a warm reload is ~50 ms
    }
}
