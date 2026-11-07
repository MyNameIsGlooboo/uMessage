package com.glooboo.umessage.messaging

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MmsPartNameTest {

    @Test
    fun blankNameWithIdUsesIdAndMimeExtension() {
        assertThat(mmsPartName("", 42L, "image/jpeg", 7L)).isEqualTo("part_42.jpg")
        assertThat(mmsPartName("   ", 42L, "image/jpeg", 7L)).isEqualTo("part_42.jpg")
    }

    @Test
    fun nullIdUsesTheStamp() {
        assertThat(mmsPartName(null, null, "image/jpeg", 7L)).isEqualTo("part_7.jpg")
    }

    @Test
    fun unknownMimeTypeFallsBackToBin() {
        assertThat(mmsPartName("", 1L, "application/x-nothing-known", 7L)).isEqualTo("part_1.bin")
        assertThat(mmsPartName("", 1L, "", 7L)).isEqualTo("part_1.bin")
    }

    @Test
    fun existingNameIsKeptWithoutPathOrSpaces() {
        assertThat(mmsPartName("photo.jpg", 1L, "image/jpeg", 7L)).isEqualTo("photo.jpg")
        assertThat(mmsPartName("a/b\my pic.jpg", 1L, "image/jpeg", 7L)).isEqualTo("my_pic.jpg")
    }
}
