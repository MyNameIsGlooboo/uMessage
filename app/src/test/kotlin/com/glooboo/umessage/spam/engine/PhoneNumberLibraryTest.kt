package com.glooboo.umessage.spam.engine

import com.google.i18n.phonenumbers.PhoneNumberUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the phone number library's metadata where an old copy is wrong (M-B3): libphonenumber 8.13.50 read the 0904
 * mobile range in IR as invalid, so a contact's new number looked unknown. The numbers are made up (all zeros).
 */
class PhoneNumberLibraryTest {

    private val util = PhoneNumberUtil.getInstance()

    @Test fun aNumberInThe0904RangeIsAValidMobileNumber() {
        val number = util.parse("09040000000", "IR")
        assertTrue(util.isValidNumber(number))
        assertEquals(PhoneNumberUtil.PhoneNumberType.MOBILE, util.getNumberType(number))
    }

    @Test fun theUnassignedTestRangeStaysInvalid() {
        // The dummy numbers of the detector tests (0906...) must stay outside every real range.
        assertFalse(util.isValidNumber(util.parse("09060000000", "IR")))
    }
}
