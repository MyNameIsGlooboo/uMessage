package com.glooboo.umessage.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TU-18: a huge photo was decoded at full size (about 800 MB for 200 MP) and could crash the app. */
class ImageCompressorTest {

    @Test
    fun hugePhotoIsDecodedAtABoundedSize() {
        // A 200 MP camera photo.
        val sample = ImageCompressor.sampleSizeFor(16_320, 12_240, ImageCompressor.MAX_DECODE_EDGE)
        assertEquals(8, sample)
        val pixels = (16_320L / sample) * (12_240L / sample)
        assertTrue("decoded bitmap is $pixels pixels", pixels * 4 < 20L * 1024 * 1024)
    }

    @Test
    fun smallPhotoIsDecodedAsIs() {
        assertEquals(1, ImageCompressor.sampleSizeFor(1_600, 1_200, ImageCompressor.MAX_DECODE_EDGE))
        assertEquals(1, ImageCompressor.sampleSizeFor(2_048, 1_536, ImageCompressor.MAX_DECODE_EDGE))
        assertEquals(2, ImageCompressor.sampleSizeFor(2_049, 100, ImageCompressor.MAX_DECODE_EDGE))
    }
}
