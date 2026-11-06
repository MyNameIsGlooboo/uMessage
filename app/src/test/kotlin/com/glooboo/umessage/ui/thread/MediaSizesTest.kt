package com.glooboo.umessage.ui.thread

import android.app.Application
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.models.Attachment
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.MessageAttachment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** TU-25: a photo bubble starts at the photo's own shape once it has been seen, so the chat does not jump. */
@RunWith(AndroidJUnit4::class)
class MediaSizesTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val uri = "content://mms/part/42"

    @After
    fun tearDown() = MediaSizes.clearMemory()

    private fun photoMessage() = Message(
        id = 7, body = "", type = Telephony.Sms.MESSAGE_TYPE_INBOX, status = 0, participants = ArrayList(),
        date = 0, read = true, threadId = 1, isMMS = true,
        attachment = MessageAttachment(7, "", arrayListOf(Attachment(42, 7, uri, "image/jpeg", 0, 0, ""))),
        senderPhoneNumber = "", senderName = "", senderPhotoUri = "", subscriptionId = -1,
    )

    @Test
    fun anUnseenPhotoStartsAsFourByThree() {
        assertNull(MediaSizes.cached(uri))
        assertEquals(4f / 3f, mediaAspect(null, null, MediaSizes.cached(uri)), 0.001f)
    }

    @Test
    fun aTallPhotoSeenOnceStartsTallNextTime() {
        MediaSizes.store(app, uri, width = 600, height = 1200)
        // The app was closed: only the saved copy is left.
        MediaSizes.clearMemory()
        assertNull(MediaSizes.cached(uri))

        MediaSizes.warm(app, listOf(photoMessage()))

        assertEquals(0.5f, mediaAspect(null, null, MediaSizes.cached(uri)), 0.001f)
    }

    @Test
    fun theLoadedPictureWins() {
        assertEquals(2f, mediaAspect(800, 400, 0.5f), 0.001f)
    }
}
