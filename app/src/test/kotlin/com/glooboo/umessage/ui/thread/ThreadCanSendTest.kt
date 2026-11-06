package com.glooboo.umessage.ui.thread

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.models.AttachmentSelection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** TU-04 and TU-05: when the Send button is on. */
@RunWith(AndroidJUnit4::class)
class ThreadCanSendTest {

    private val vm = ThreadViewModel(SavedStateHandle())

    private fun photo(pending: Boolean) =
        AttachmentSelection("photo", "content://media/1".toUri(), "image/jpeg", "IMG.jpg", isPending = pending)

    @Test
    fun emptyComposerCannotSend() {
        assertFalse(vm.canSend)
    }

    @Test
    fun spacesAndLineBreaksAloneCannotSend() {
        vm.text.setTextAndPlaceCursorAtEnd("   \n\t ")
        assertFalse(vm.canSend)
    }

    @Test
    fun realTextCanSend() {
        vm.text.setTextAndPlaceCursorAtEnd("  hi ")
        assertTrue(vm.canSend)
    }

    @Test
    fun textWithAPhotoStillShrinkingCannotSend() {
        vm.text.setTextAndPlaceCursorAtEnd("Look at this!")
        vm.addAttachment(photo(pending = true))
        assertFalse(vm.canSend)

        vm.replaceAttachment("photo", photo(pending = false))
        assertTrue(vm.canSend)
    }

    @Test
    fun aReadyPhotoAloneCanSend() {
        vm.addAttachment(photo(pending = false))
        assertTrue(vm.canSend)
    }
}
