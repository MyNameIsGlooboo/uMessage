package com.glooboo.umessage.ui.thread

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Looper
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.THREAD_ID
import com.glooboo.umessage.helpers.THREAD_TEXT
import com.glooboo.umessage.models.AttachmentSelection
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLooper

/** M-UI5: "Done" in the people panel opens the other chat without a provider query on the main thread, draft kept. */
@RunWith(AndroidJUnit4::class)
class ThreadPeopleControllerTest {

    /** The provider's thread lookup (content://mms-sms/threadID): always chat 42, noting the thread it ran on. */
    class ThreadIdProvider : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor {
            queriedOnMain = Looper.myLooper() == Looper.getMainLooper()
            return MatrixCursor(arrayOf("_id")).apply { addRow(arrayOf<Any>(NEW_THREAD)) }
        }

        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun update(uri: Uri, values: ContentValues?, s: String?, a: Array<out String>?) = 0
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun getType(uri: Uri): String? = null
    }

    @Test
    fun doneLooksTheChatUpOffTheMainThreadAndKeepsTheText() {
        queriedOnMain = null
        Robolectric.setupContentProvider(ThreadIdProvider::class.java, "mms-sms")
        val activity = Robolectric.buildActivity(Activity::class.java).create().get()
        val vm = ThreadViewModel()
        vm.text.setTextAndPlaceCursorAtEnd("On my way")
        val photo = Uri.parse("content://media/external/images/media/7")
        vm.addAttachment(AttachmentSelection(photo.toString(), photo, "image/jpeg", "photo.jpg", isPending = false))
        val people = ThreadPeopleController(
            activity = activity,
            vm = vm,
            recipients = { ThreadRecipients.fromNumbers(listOf("+15550001234")) },
            setRecipients = {},
            privateContacts = { emptyList() },
            threadId = { 5L },
            onPeopleChanged = {},
        )

        people.confirm()
        val opened = awaitStarted(activity)

        assertThat(queriedOnMain).isFalse()
        assertThat(opened.getLongExtra(THREAD_ID, 0L)).isEqualTo(NEW_THREAD)
        assertThat(opened.getStringExtra(THREAD_TEXT)).isEqualTo("On my way")
        assertThat(opened.getBooleanExtra(ThreadPeopleController.EXTRA_KEEP_ATTACHMENTS, false)).isTrue()
        // Moved, not copied: this chat no longer has it, so it is not saved here as a draft.
        assertThat(vm.text.text.toString()).isEmpty()
    }

    private fun awaitStarted(activity: Activity): Intent {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            shadowOf(activity).nextStartedActivity?.let { return it }
            Thread.sleep(POLL_MS)
        }
        error("The other chat was not opened")
    }

    companion object {
        const val NEW_THREAD = 42L
        const val WAIT_MS = 5_000L
        const val POLL_MS = 10L

        @Volatile
        var queriedOnMain: Boolean? = null
    }
}
