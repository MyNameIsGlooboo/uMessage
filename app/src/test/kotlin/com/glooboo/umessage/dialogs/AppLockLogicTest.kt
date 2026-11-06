package com.glooboo.umessage.dialogs

import org.junit.Assert.assertEquals
import org.fossify.commons.helpers.AppLockSecurity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.fossify.commons.helpers.PROTECTION_FINGERPRINT
import org.fossify.commons.helpers.PROTECTION_PATTERN
import org.fossify.commons.helpers.PROTECTION_PIN
import org.fossify.commons.helpers.SHOW_ALL_TABS

class AppLockDialogTabsTest {
    @Test
    fun `setting up starts on PIN and offers biometrics only when supported`() {
        assertEquals(listOf(PROTECTION_PIN, PROTECTION_PATTERN, PROTECTION_FINGERPRINT), dialogTabs(SHOW_ALL_TABS, "", true))
        assertEquals(listOf(PROTECTION_PIN, PROTECTION_PATTERN), dialogTabs(SHOW_ALL_TABS, "", false))
    }

    @Test
    fun `checking a biometric lock also offers its backup pin`() {
        assertEquals(listOf(PROTECTION_FINGERPRINT, PROTECTION_PIN), dialogTabs(PROTECTION_FINGERPRINT, "stored", true))
        assertEquals(listOf(PROTECTION_FINGERPRINT), dialogTabs(PROTECTION_FINGERPRINT, "", true))
        assertEquals(listOf(PROTECTION_PATTERN), dialogTabs(PROTECTION_PATTERN, "stored", true))
    }
}

class AppLockLogicTest {
    @Test
    fun `pin hash is lowercase hex sha1`() {
        assertEquals("7110eda4d09e062aa5e4a390b0a572ac0d2c0220", AppLockLogic.hashPin("1234"))
        assertEquals(40, AppLockLogic.hashPin("0").length)
    }

    @Test
    fun `pattern hash uses concatenated cell numbers`() {
        // "0124" -> same text as a PIN of those digits; the stored format is shared
        assertEquals(AppLockLogic.sha1Hex("0124"), AppLockLogic.hashPattern(listOf(0, 1, 2, 4)))
    }

    @Test
    fun `pattern takes the dot in between`() {
        assertEquals(listOf(0, 1, 2), AppLockLogic.addPatternCell(listOf(0), 2))
        assertEquals(listOf(0, 4, 8), AppLockLogic.addPatternCell(listOf(0), 8))
        assertEquals(listOf(0, 3, 6), AppLockLogic.addPatternCell(listOf(0), 6))
    }

    @Test
    fun `pattern does not repeat dots or skip visited gap`() {
        assertEquals(listOf(1, 0, 2), AppLockLogic.addPatternCell(listOf(1, 0), 2))
        assertEquals(listOf(0, 1), AppLockLogic.addPatternCell(listOf(0, 1), 0))
        // knight move has no dot in between
        assertEquals(listOf(0, 5), AppLockLogic.addPatternCell(listOf(0), 5))
        assertEquals(listOf(4), AppLockLogic.addPatternCell(emptyList(), 4))
    }

    @Test
    fun `lockout counts down after too many tries`() {
        assertEquals(0, AppLockLogic.lockoutSeconds(2, 9_000, 10_000))
        assertEquals(5, AppLockLogic.lockoutSeconds(3, 10_000, 10_000))
        assertEquals(3, AppLockLogic.lockoutSeconds(3, 8_000, 10_000))
        assertEquals(0, AppLockLogic.lockoutSeconds(3, 1_000, 10_000))
        // no recorded failure time (older versions): nothing to wait for
        assertEquals(0, AppLockLogic.lockoutSeconds(3, 0, 10_000))
    }

    @Test
    fun `lockout doubles with each further wrong try and is capped`() {
        assertEquals(10, AppLockLogic.lockoutSeconds(4, 10_000, 10_000))
        assertEquals(20, AppLockLogic.lockoutSeconds(5, 10_000, 10_000))
        assertEquals(40, AppLockLogic.lockoutSeconds(6, 10_000, 10_000))
        assertEquals(AppLockSecurity.MAX_LOCKOUT_SECONDS, AppLockLogic.lockoutSeconds(50, 10_000, 10_000))
    }

    @Test
    fun `lockout ignores a clock that went backwards`() {
        assertEquals(5, AppLockLogic.lockoutSeconds(3, 10_000, 4_000))
    }

    @Test
    fun `stored value is salted pbkdf2 and verifies`() {
        val entryHash = AppLockLogic.hashPin("1234")
        val stored = AppLockLogic.protect(entryHash)
        assertTrue(stored.startsWith("pbkdf2-sha256$"))
        assertNotEquals(stored, AppLockLogic.protect(entryHash))
        assertTrue(AppLockLogic.verify(stored, entryHash))
        assertFalse(AppLockLogic.verify(stored, AppLockLogic.hashPin("1235")))
        assertFalse(AppLockLogic.verify("pbkdf2-sha256\$x\$zz\$00", entryHash))
        assertFalse(AppLockLogic.verify("", entryHash))
        assertEquals(stored, AppLockLogic.upgrade(stored, entryHash))
    }

    @Test
    fun `legacy sha1 still verifies and is upgraded`() {
        val legacy = AppLockLogic.hashPin("1234")
        assertTrue(AppLockSecurity.isLegacy(legacy))
        assertTrue(AppLockLogic.verify(legacy, legacy))
        assertFalse(AppLockLogic.verify(legacy, AppLockLogic.hashPin("0000")))
        val upgraded = AppLockLogic.upgrade(legacy, legacy)
        assertFalse(AppLockSecurity.isLegacy(upgraded))
        assertTrue(AppLockLogic.verify(upgraded, legacy))
    }

    @Test
    fun `setting up asks twice and rejects a different repeat`() {
        val entry = CredentialEntry("") { "stored:$it" }
        assertFalse(entry.isRepeating)
        assertEquals(CredentialEntry.Result.Repeat, entry.submit("aaa"))
        assertTrue(entry.isRepeating)
        assertEquals(CredentialEntry.Result.Wrong, entry.submit("bbb"))
        assertFalse(entry.isRepeating)
        assertEquals(CredentialEntry.Result.Repeat, entry.submit("bbb"))
        assertEquals(CredentialEntry.Result.Correct("stored:bbb"), entry.submit("bbb"))
    }

    @Test
    fun `checking compares with the stored hash and can retry`() {
        val pin = AppLockLogic.hashPin("2580")
        val stored = AppLockLogic.protect(pin)
        val entry = CredentialEntry(stored)
        assertFalse(entry.isRepeating)
        assertEquals(CredentialEntry.Result.Wrong, entry.submit(AppLockLogic.hashPin("0000")))
        assertEquals(CredentialEntry.Result.Correct(stored), entry.submit(pin))
    }

    @Test
    fun `checking against a legacy hash returns its salted replacement`() {
        val pin = AppLockLogic.hashPin("2580")
        val result = CredentialEntry(pin).submit(pin)
        assertTrue(result is CredentialEntry.Result.Correct)
        val replacement = (result as CredentialEntry.Result.Correct).hash
        assertFalse(AppLockSecurity.isLegacy(replacement))
        assertTrue(AppLockLogic.verify(replacement, pin))
    }
}

class CredentialStrengthTest {
    @Test
    fun `a new pattern needs at least four dots`() {
        assertEquals(4, AppLockLogic.MIN_PATTERN_DOTS)
        val entry = CredentialEntry("", minLength = AppLockLogic.MIN_PATTERN_DOTS) { "stored:$it" }
        assertEquals(CredentialEntry.Result.TooShort, entry.submit(AppLockLogic.hashPattern(listOf(4)), length = 1))
        assertEquals(CredentialEntry.Result.TooShort, entry.submit(AppLockLogic.hashPattern(listOf(0, 1, 2)), length = 3))
        assertFalse(entry.isRepeating)
        val four = AppLockLogic.hashPattern(listOf(0, 1, 2, 5))
        assertEquals(CredentialEntry.Result.Repeat, entry.submit(four, length = 4))
        assertEquals(CredentialEntry.Result.Correct("stored:$four"), entry.submit(four, length = 4))
    }

    @Test
    fun `a new pin needs at least four digits`() {
        val entry = CredentialEntry("", minLength = AppLockLogic.MIN_PIN_LENGTH) { "stored:$it" }
        assertEquals(CredentialEntry.Result.TooShort, entry.submit(AppLockLogic.hashPin("123"), length = 3))
        assertEquals(CredentialEntry.Result.Repeat, entry.submit(AppLockLogic.hashPin("1234"), length = 4))
    }

    @Test
    fun `an existing short credential is still accepted when checking`() {
        val one = AppLockLogic.hashPattern(listOf(4))
        val entry = CredentialEntry(AppLockLogic.protect(one), minLength = AppLockLogic.MIN_PATTERN_DOTS)
        assertTrue(entry.submit(one, length = 1) is CredentialEntry.Result.Correct)
    }

    @Test
    fun `weakness is judged by the length of the entry`() {
        assertTrue(AppLockLogic.isWeak(PROTECTION_PATTERN, 1))
        assertTrue(AppLockLogic.isWeak(PROTECTION_PATTERN, 3))
        assertFalse(AppLockLogic.isWeak(PROTECTION_PATTERN, 4))
        assertTrue(AppLockLogic.isWeak(PROTECTION_PIN, 3))
        assertFalse(AppLockLogic.isWeak(PROTECTION_PIN, 4))
        assertFalse(AppLockLogic.isWeak(PROTECTION_FINGERPRINT, 4))
    }
}

class PatternGeometryTest {
    @Test
    fun `touch near a dot hits it`() {
        // 300x300 pad: cell 100, centres at 50/150/250
        assertEquals(0, PatternGeometry.cellAt(50f, 50f, 300f, 300f))
        assertEquals(4, PatternGeometry.cellAt(160f, 140f, 300f, 300f))
        assertEquals(8, PatternGeometry.cellAt(250f, 270f, 300f, 300f))
    }

    @Test
    fun `touch between dots or outside misses`() {
        org.junit.Assert.assertNull(PatternGeometry.cellAt(100f, 100f, 300f, 300f))
        org.junit.Assert.assertNull(PatternGeometry.cellAt(-5f, 50f, 300f, 300f))
        org.junit.Assert.assertNull(PatternGeometry.cellAt(310f, 50f, 300f, 300f))
        org.junit.Assert.assertNull(PatternGeometry.cellAt(10f, 10f, 0f, 0f))
    }
}
