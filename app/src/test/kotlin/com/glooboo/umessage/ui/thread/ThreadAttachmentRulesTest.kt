package com.glooboo.umessage.ui.thread

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.FILE_SIZE_NONE
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** Which picked files can be attached, and the camera's target surviving recreation (TU-50). */
@RunWith(AndroidJUnit4::class)
class ThreadAttachmentRulesTest {

    @Test
    fun stillImagesAreCompressedWhateverTheirSize() {
        val check = checkAttachment(alreadyAdded = false, mimeType = "image/jpeg", sizeLimit = 100) { error("not read") }
        assertThat(check).isEqualTo(AttachmentCheck.Ok("image/jpeg", compress = true))
    }

    @Test
    fun otherFilesAndGifsMustFitTheLimit() {
        assertThat(checkAttachment(false, "image/gif", sizeLimit = 100) { 101 }).isEqualTo(AttachmentCheck.TooLarge)
        assertThat(checkAttachment(false, "video/mp4", sizeLimit = 100) { 100 })
            .isEqualTo(AttachmentCheck.Ok("video/mp4", compress = false))
        assertThat(checkAttachment(false, "video/mp4", sizeLimit = FILE_SIZE_NONE) { Long.MAX_VALUE })
            .isEqualTo(AttachmentCheck.Ok("video/mp4", compress = false))
    }

    @Test
    fun duplicatesAndUnknownTypesAreRefused() {
        assertThat(checkAttachment(true, "image/jpeg", sizeLimit = 100) { 1 }).isEqualTo(AttachmentCheck.Duplicate)
        assertThat(checkAttachment(false, null, sizeLimit = 100) { 1 }).isEqualTo(AttachmentCheck.UnknownType)
    }

    @Test
    fun theCameraTargetSurvivesTheProcessBeingKilled() {
        val handle = SavedStateHandle()
        ThreadViewModel(handle).capturedImageUri = Uri.parse("content://files/attachment_1.jpg")
        assertThat(ThreadViewModel(handle).capturedImageUri).isEqualTo(Uri.parse("content://files/attachment_1.jpg"))
    }
}
