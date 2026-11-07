package com.glooboo.umessage.export

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream

/** D9: one huge attachment is skipped (and counted by the caller), never allowed to fail a whole backup. */
class PartBytesTest {

    @Test
    fun aSmallPartIsRead() {
        val bytes = PartBytes.read(ByteArrayInputStream(ByteArray(1000) { 7 }))

        assertThat(bytes).hasLength(1000)
    }

    @Test
    fun anEmptyPartIsNull() {
        assertThat(PartBytes.read(ByteArrayInputStream(ByteArray(0)))).isNull()
    }

    @Test
    fun aPartOverTheLimitIsSkipped() {
        assertThat(PartBytes.read(ByteArrayInputStream(ByteArray(2000)), max = 1999)).isNull()
        assertThat(PartBytes.read(ByteArrayInputStream(ByteArray(2000)), max = 2000)).hasLength(2000)
    }
}
