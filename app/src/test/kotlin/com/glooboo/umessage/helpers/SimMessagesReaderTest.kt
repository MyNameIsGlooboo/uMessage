package com.glooboo.umessage.helpers

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.models.SmsBackup
import com.glooboo.umessage.models.WriteOutcome
import com.glooboo.umessage.models.WriteResult
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric

/** I62: a fake `content://sms` provider that answers the SIM lists with invented rows, nothing, or a refusal. */
class FakeIccProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? {
        mode?.let { if (it == "throw") throw SecurityException("no access") }
        if (mode == "null") return null
        val cursor = MatrixCursor(arrayOf("address", "body", "date", "status"))
        rows[uri.toString()].orEmpty().forEach { cursor.addRow(it) }
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun getType(uri: Uri): String? = null

    companion object {
        var mode: String? = null
        val rows = HashMap<String, List<Array<Any>>>()
    }
}

@RunWith(AndroidJUnit4::class)
class SimMessagesReaderTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    private fun reader(vararg subs: Int): SimMessagesReader {
        FakeIccProvider.mode = null
        FakeIccProvider.rows.clear()
        Robolectric.setupContentProvider(FakeIccProvider::class.java, Telephony.Sms.CONTENT_URI.authority)
        return SimMessagesReader(context.contentResolver) { subs.toList() }
    }

    @Test
    fun readsRowsOldestFirstAndMarksSent() {
        val reader = reader()
        FakeIccProvider.rows["content://sms/icc"] = listOf(
            arrayOf("+15550100", "Second", 2000L, 1),
            arrayOf("+15550100", "First", 1000L, 5),
            arrayOf("", "No sender", 3000L, 1),
        )
        val result = reader.read() as SimReadResult.Messages
        assertThat(result.messages.map { it.body }).containsExactly("First", "Second").inOrder()
        assertThat(result.messages.map { it.sent }).containsExactly(true, false).inOrder()
    }

    @Test
    fun emptyCardIsAnAnswerNotARefusal() {
        assertThat(reader().read()).isEqualTo(SimReadResult.Messages(emptyList()))
    }

    @Test
    fun nullCursorAndSecurityExceptionAreUnavailable() {
        val reader = reader()
        FakeIccProvider.mode = "null"
        assertThat(reader.read()).isEqualTo(SimReadResult.Unavailable)
        FakeIccProvider.mode = "throw"
        assertThat(reader.read()).isEqualTo(SimReadResult.Unavailable)
    }

    @Test
    fun readsPerSimAndMergesSameText() {
        val reader = reader(3, 4)
        val same = arrayOf<Any>("+15550101", "Hello", 5000L, 1)
        FakeIccProvider.rows["content://sms/icc_subId/3"] = listOf(same)
        FakeIccProvider.rows["content://sms/icc_subId/4"] = listOf(same, arrayOf("+15550102", "Other", 6000L, 3))
        val result = reader.read() as SimReadResult.Messages
        assertThat(result.messages.map { it.body }).containsExactly("Hello", "Other").inOrder()
    }

    @Test
    fun importCountsAddedDuplicatesAndFailures() {
        val seen = HashSet<String>()
        val written = mutableListOf<SmsBackup>()
        val importer = SimMessagesImporter { sms ->
            when {
                sms.address == "bad" -> WriteResult(WriteOutcome.FAILED, "x")
                !seen.add("${sms.address}|${sms.date}|${sms.body}") -> WriteResult(WriteOutcome.DUPLICATE)
                else -> WriteResult(WriteOutcome.ADDED).also { written += sms }
            }
        }
        val summary = importer.import(
            listOf(
                SimMessage("+15550100", "Hi", 1000L, false),
                SimMessage("+15550100", "Hi", 1000L, false),
                SimMessage("+15550100", "Sent one", 2000L, true),
                SimMessage("bad", "Nope", 3000L, false),
            )
        )
        assertThat(summary).isEqualTo(SimImportSummary(added = 2, duplicates = 1, failed = 1))
        assertThat(written.map { it.date }).containsExactly(1000L, 2000L).inOrder()
        assertThat(written.map { it.type })
            .containsExactly(Telephony.Sms.MESSAGE_TYPE_INBOX, Telephony.Sms.MESSAGE_TYPE_SENT).inOrder()
    }
}
