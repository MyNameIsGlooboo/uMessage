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
}
