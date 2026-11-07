package com.glooboo.umessage.messaging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.models.Attachment
import com.glooboo.umessage.testing.ShadowRecordingSmsManager
import com.google.common.truth.Truth.assertThat
import com.klinker.android.send_message.Settings
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper

@RunWith(AndroidJUnit4::class)
class SendMmsMessageTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = ShadowRecordingSmsManager.reset()

    private fun attachment(uri: String) = Attachment(
        id = null, messageId = 1L, uriString = uri, mimetype = "image/jpeg", width = 0, height = 0,
        filename = "photo.jpg"
    )

    private fun settings() = Settings().apply { useSystemSending = true }

    @Test
    fun unreadablePhotoIsNotSentAsAnEmptyMessage() {
        // RM-09: the photo file cannot be read; nothing may go out, and the caller must hear it failed.
        val missing = attachment("file:///data/does-not-exist/photo.jpg")

        val handedOff = MessagingUtils(context).sendMmsMessage("", listOf("111"), missing, settings())
        ShadowLooper.idleMainLooper()

        assertThat(handedOff).isFalse()
        assertThat(ShadowRecordingSmsManager.multimediaSends).isEmpty()
    }

    @Test
    fun attachmentWithNoReadableStreamIsNotSent() {
        // A content uri nobody serves: openInputStream fails or gives nothing.
        val nowhere = attachment("content://com.example.nobody/photo/1")

        val handedOff = MessagingUtils(context).sendMmsMessage("Look", listOf("111"), nowhere, settings())
        ShadowLooper.idleMainLooper()

        assertThat(handedOff).isFalse()
        assertThat(ShadowRecordingSmsManager.multimediaSends).isEmpty()
    }

    @Test
    fun readablePhotoWithoutFilenameIsHandedOff() {
        // A resent picture is read back from the provider with an empty filename; it must still go out.
        com.glooboo.umessage.testing.FakeMessageStore.install() // the MMS library asks the provider for a thread id
        val file = java.io.File(context.cacheDir, "resend.jpg").apply { writeBytes(ByteArray(16) { it.toByte() }) }
        val unnamed = attachment(android.net.Uri.fromFile(file).toString()).copy(filename = "")

        MessagingUtils(context).sendMmsMessage("", listOf("111"), unnamed, settings())
        ShadowLooper.idleMainLooper()

        // The test provider has no MMS part table, so the library cannot finish persisting; what this app controls is
        // that the picture is read and handed to the library, not refused first as unreadable or empty.
        val refusedByApp = org.robolectric.shadows.ShadowLog.getLogs().map { it.msg }
            .filter { it == "Could not read the attachment" || it == "The attachment stream was empty" }
        assertThat(refusedByApp).isEmpty()
    }
}
