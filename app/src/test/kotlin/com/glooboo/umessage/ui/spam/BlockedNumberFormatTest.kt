package com.glooboo.umessage.ui.spam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockedNumberFormatTest {
    @Test
    fun normalizeTrimsAndTurnsRegexWildcardIntoStar() {
        assertEquals("+1555*", BlockedNumberFormat.normalizeInput("  +1555.*  "))
        assertEquals("+14155550133", BlockedNumberFormat.normalizeInput("+14155550133"))
    }

    @Test
    fun importReadsFossifyCommaFormat() {
        assertEquals(
            listOf("+14155550133", "+91140*", "12345"),
            BlockedNumberFormat.parseImport("+14155550133,+91140*, 12345"),
        )
    }

    @Test
    fun importReadsOnePerLineAndDropsBlanksAndDuplicates() {
        assertEquals(
            listOf("+14155550133", "+91140*"),
            BlockedNumberFormat.parseImport("+14155550133\r\n\n+91140.*\n+14155550133\n  \n"),
        )
    }

    @Test
    fun importOfEmptyFileIsEmpty() {
        assertTrue(BlockedNumberFormat.parseImport(" \n , ").isEmpty())
    }

    @Test
    fun exportRoundTripsThroughImport() {
        val numbers = listOf("+14155550133", "+91140*")
        assertEquals(numbers, BlockedNumberFormat.parseImport(BlockedNumberFormat.exportText(numbers)))
    }

    @Test
    fun patternIsDetectedByStar() {
        assertTrue(BlockedNumberFormat.isPattern("+1555*"))
        assertFalse(BlockedNumberFormat.isPattern("+15550100"))
    }

    @Test
    fun contactsComeFirstByNameThenNumbers() {
        val sorted = BlockedNumberFormat.sorted(
            listOf(
                BlockedNumberItem(1, "+3"),
                BlockedNumberItem(2, "+2", "Zed"),
                BlockedNumberItem(3, "+1"),
                BlockedNumberItem(4, "+4", "Amy"),
            ),
        )
        assertEquals(listOf(4L, 2L, 3L, 1L), sorted.map { it.id })
    }

    @Test
    fun rulesFileImportedAsNumbersKeepsOnlyNumbers() {
        // SU-32: words from a rules file used to land in the phone-wide block list.
        val result = BlockedNumberFormat.parseImportChecked("BLOCK,NUMBER,+44712 111 2222\nALLOW,KEYWORD,delivery code\n*\n12\n")
        assertEquals(listOf("+44712 111 2222"), result.numbers)
        assertEquals(7, result.skipped)
    }

    @Test
    fun onlyNumbersAndWildcardsAreValidEntries() {
        assertTrue(BlockedNumberFormat.isValidEntry("+1 (415) 555-0133"))
        assertTrue(BlockedNumberFormat.isValidEntry("+91140*"))
        assertTrue(BlockedNumberFormat.isValidEntry("+91140.*"))
        assertFalse(BlockedNumberFormat.isValidEntry("*"))
        assertFalse(BlockedNumberFormat.isValidEntry("12"))
        assertFalse(BlockedNumberFormat.isValidEntry("call me"))
        assertFalse(BlockedNumberFormat.isValidEntry("44+712"))
    }
}
