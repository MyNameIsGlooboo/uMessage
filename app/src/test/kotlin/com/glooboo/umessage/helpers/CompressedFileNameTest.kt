package com.glooboo.umessage.helpers

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CompressedFileNameTest {
    @Test
    fun picturesPickedTogetherGetDifferentFiles() {
        val names = List(1_000) { compressedFileName(".jpg") }

        assertThat(names.toSet()).hasSize(1_000)
    }

    @Test
    fun theExtensionIsKept() {
        assertThat(compressedFileName(".png")).endsWith(".png")
    }
}
