package com.glooboo.umessage.ui.thread

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.models.Attachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T7 / TU-01 and TU-23: state the activity used to keep in its own fields, lost on rotation. A new view model on the
 * same [SavedStateHandle] stands in for the one Android builds after the process was killed.
 */
@RunWith(AndroidJUnit4::class)
class ThreadViewModelStateTest {

    @Test
    fun scheduleSurvivesRecreation() {
        val handle = SavedStateHandle()
        ThreadViewModel(handle).apply {
            scheduledMillis = 1_800_000_000_000L
            scheduledMessageId = 42L
        }

        val restored = ThreadViewModel(handle)
        assertTrue(restored.isScheduling)
        assertEquals(1_800_000_000_000L, restored.scheduledMillis)
        // The attachments of the edit are not saved, so the edit id is dropped (the message is saved as a new one).
        assertNull(restored.scheduledMessageId)
    }

    @Test
    fun clearingTheScheduleSendsNowAgain() {
        val vm = ThreadViewModel(SavedStateHandle())
        vm.scheduledMillis = 1_800_000_000_000L
        vm.scheduledMessageId = 7L
        vm.scheduledLabel = "9:00 AM"

        vm.clearSchedule()

        assertFalse(vm.isScheduling)
        assertNull(vm.scheduledMessageId)
        assertNull(vm.scheduledLabel)
    }

    @Test
    fun attachmentsToSaveSurviveRecreation() {
        val handle = SavedStateHandle()
        val photo = Attachment(null, 5, "content://mms/part/12", "image/jpeg", 0, 0, "IMG_1.jpg")
        val clip = Attachment(null, 5, "content://mms/part/13", "video/mp4", 0, 0, "")
        ThreadViewModel(handle).attachmentsToSave = listOf(photo, clip)

        val restored = ThreadViewModel(handle).attachmentsToSave!!
        assertEquals(listOf(photo.uriString, clip.uriString), restored.map { it.uriString })
        assertEquals(listOf("image/jpeg", "video/mp4"), restored.map { it.mimetype })
        assertEquals(listOf("IMG_1.jpg", ""), restored.map { it.filename })

        ThreadViewModel(handle).attachmentsToSave = null
        assertNull(ThreadViewModel(handle).attachmentsToSave)
    }
}
