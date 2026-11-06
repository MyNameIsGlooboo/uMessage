package com.glooboo.umessage.extensions

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric

/**
 * The main screen deletes every cached conversation missing from a full provider read. A read that failed must
 * therefore come back as null, never as an empty list.
 */
@RunWith(AndroidJUnit4::class)
class ConversationsReadTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun missingProviderIsAFailedReadNotAnEmptyInbox() {
        assertThat(context.getConversationsOrNull()).isNull()
        // The lenient variant used elsewhere still returns an empty list.
        assertThat(context.getConversations()).isEmpty()
    }

    @Test
    fun providerErrorIsAFailedRead() {
        FakeThreadsProvider.failWith = IllegalStateException("provider died")
        Robolectric.buildContentProvider(FakeThreadsProvider::class.java).create(THREADS_AUTHORITY)

        assertThat(context.getConversationsOrNull()).isNull()
    }

    @Test
    fun emptyCursorIsARealEmptyInbox() {
        FakeThreadsProvider.failWith = null
        Robolectric.buildContentProvider(FakeThreadsProvider::class.java).create(THREADS_AUTHORITY)

        assertThat(context.getConversationsOrNull()).isEmpty()
    }

    class FakeThreadsProvider : ContentProvider() {
        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            failWith?.let { throw it }
            return MatrixCursor(projection ?: arrayOf("_id"))
        }

        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

        companion object {
            @Volatile var failWith: RuntimeException? = null
        }
    }

    private companion object {
        const val THREADS_AUTHORITY = "mms-sms"
    }
}
