package com.glooboo.umessage.spam.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberNormalizerTest {

    private val india = NumberNormalizer { "IN" }
    private val us = NumberNormalizer { "US" }

    @Test fun plusFormat() = assertEquals("+919876543210", india.normalize("+91 98765 43210"))

    @Test fun doubleZeroFormat() = assertEquals("+919876543210", india.normalize("0091 98765-43210"))

    @Test fun localZeroFormat() = assertEquals("+919876543210", india.normalize("098765 43210"))

    @Test fun nationalFormat() = assertEquals("+919876543210", india.normalize("9876543210"))

    @Test fun dashesAndParentheses() = assertEquals("+14155552671", us.normalize("(415) 555-2671"))

    @Test fun plusIgnoresRegion() = assertEquals("+14155552671", india.normalize("+1 415-555-2671"))

    @Test fun alphanumericSenderIsUpperCased() = assertEquals("VM-HDFCBK", india.normalize(" vm-hdfcbk "))

    @Test fun shortCodeKept() = assertEquals("56161", india.normalize("56161"))

    @Test fun blankStaysBlank() = assertEquals("", india.normalize("  "))

    @Test fun sameAddressAcrossFormats() {
        assertTrue(india.sameAddress("+91 98765 43210", "09876543210"))
        assertTrue(india.sameAddress("0091-9876543210", "9876543210"))
        assertFalse(india.sameAddress("+919876543210", "+919876543211"))
        assertFalse(india.sameAddress("", ""))
    }
}
