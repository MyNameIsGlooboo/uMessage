package com.glooboo.umessage.helpers

import com.glooboo.umessage.helpers.BackupCrypto.CHUNK_SIZE
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random

/** The password on an exported file: round trips at every chunk edge, wrong passwords and damaged files. */
class BackupCryptoTest {
    private val password = "correct horse".toCharArray()

    // Few iterations keep the tests fast; the file carries its own count, so decrypt needs no matching setting.
    private fun seal(data: ByteArray, pass: CharArray = password): ByteArray {
        val out = ByteArrayOutputStream()
        BackupCrypto.encrypt(out, pass, iterations = TEST_ITERATIONS).use { it.write(data) }
        return out.toByteArray()
    }

    private fun open(file: ByteArray, pass: CharArray = password): ByteArray =
        BackupCrypto.decrypt(file.inputStream(), pass.copyOf()).use { it.readBytes() }

    private fun bytes(size: Int) = ByteArray(size).also { Random(size.toLong()).nextBytes(it) }

    @Test
    fun roundTrip_returnsTheSameBytes() {
        val data = "[{\"body\":\"hello\"}]".toByteArray()
        assertThat(open(seal(data))).isEqualTo(data)
    }

    @Test
    fun roundTrip_worksAtEveryChunkEdge() {
        for (size in listOf(0, 1, CHUNK_SIZE - 1, CHUNK_SIZE, CHUNK_SIZE + 1, 2 * CHUNK_SIZE, 2 * CHUNK_SIZE + 5)) {
            val data = bytes(size)
            assertThat(open(seal(data))).isEqualTo(data)
        }
    }

    @Test
    fun roundTrip_acceptsManySmallWrites() {
        val data = bytes(CHUNK_SIZE + 1000)
        val out = ByteArrayOutputStream()
        BackupCrypto.encrypt(out, password, TEST_ITERATIONS).use { stream ->
            data.toList().chunked(777).forEach { stream.write(it.toByteArray()) }
            stream.write(1)
        }
        assertThat(open(out.toByteArray())).isEqualTo(data + 1.toByte())
    }

    @Test
    fun unicodePassword_works() {
        val pass = "گذرواژه-🔒-é".toCharArray()
        val data = bytes(100)
        assertThat(open(seal(data, pass.copyOf()), pass)).isEqualTo(data)
    }

    @Test
    fun accentsTypedInDifferentForms_areTheSamePassword() {
        val composed = "café".toCharArray()
        val decomposed = "café".toCharArray()
        val data = bytes(10)
        assertThat(open(seal(data, composed), decomposed)).isEqualTo(data)
    }

    @Test
    fun wrongPassword_isReportedAtOnce() {
        val file = seal(bytes(50))
        assertThrows(BackupCrypto.WrongPasswordException::class.java) { open(file, "wrong".toCharArray()) }
    }

    @Test
    fun sameContentSealedTwice_looksDifferent() {
        val data = bytes(50)
        assertThat(seal(data)).isNotEqualTo(seal(data))
    }

    @Test
    fun flippedByteInTheContent_isDamage_notAWrongPassword() {
        val file = seal(bytes(CHUNK_SIZE + 100))
        file[file.size - 20] = (file[file.size - 20].toInt() xor 1).toByte()
        assertThrows(BackupCrypto.DamagedFileException::class.java) { open(file) }
    }

    @Test
    fun flippedByteInTheFirstChunk_isDamage() {
        val file = seal(bytes(CHUNK_SIZE + 100))
        file[HEADER_SIZE + 5] = (file[HEADER_SIZE + 5].toInt() xor 1).toByte()
        assertThrows(BackupCrypto.DamagedFileException::class.java) { open(file) }
    }

    @Test
    fun fileCutInTheMiddleOfAChunk_isDamage() {
        val file = seal(bytes(2 * CHUNK_SIZE + 10))
        assertThrows(BackupCrypto.DamagedFileException::class.java) { open(file.copyOf(file.size - 30)) }
    }

    @Test
    fun fileCutExactlyBetweenChunks_isDamage() {
        val file = seal(bytes(2 * CHUNK_SIZE + 10))
        val cut = HEADER_SIZE + CHUNK_SIZE + TAG_SIZE
        assertThrows(BackupCrypto.DamagedFileException::class.java) { open(file.copyOf(cut)) }
    }

    @Test
    fun fileWithTheLastChunkRemoved_isDamage() {
        val file = seal(bytes(CHUNK_SIZE + 10))
        assertThrows(BackupCrypto.DamagedFileException::class.java) {
            open(file.copyOf(HEADER_SIZE + CHUNK_SIZE + TAG_SIZE))
        }
    }

    @Test
    fun emptyFileAndShortFile_areDamage() {
        assertThrows(BackupCrypto.DamagedFileException::class.java) { open(ByteArray(0)) }
        assertThrows(BackupCrypto.DamagedFileException::class.java) { open("UMSGENC1".toByteArray()) }
    }

    @Test
    fun aFileThatIsNotProtected_isNotDecrypted() {
        val plain = "[{\"a\":1}]".toByteArray()
        assertThat(BackupCrypto.isProtected(plain.inputStream().buffered())).isFalse()
        assertThrows(BackupCrypto.DamagedFileException::class.java) { open(plain) }
    }

    @Test
    fun isProtected_seesTheMarkerAndLeavesTheStreamAtTheStart() {
        val file = seal(bytes(10))
        val stream = file.inputStream().buffered()
        assertThat(BackupCrypto.isProtected(stream)).isTrue()
        assertThat(stream.readBytes()).isEqualTo(file)
    }

    @Test
    fun absurdKeySettings_areRefusedWithoutWorkingOutAKey() {
        val file = seal(bytes(10))
        file[8] = 0x7F // the iteration count follows the 8-byte marker
        assertThrows(BackupCrypto.DamagedFileException::class.java) { open(file) }
    }

    @Test
    fun aPasswordTypedOnAnotherKeyboardOpensTheFile() {
        // Arabic yeh and kaf, Arabic-Indic digits, a zero-width non-joiner: all the same password as the Persian one.
        val typedArabic = "\u0643\u0644\u064A\u062F\u0661\u0662\u0663".toCharArray()
        val typedPersian = "\u06A9\u0644\u06CC\u062F\u200C123".toCharArray()
        val data = bytes(50)

        assertThat(open(seal(data, typedArabic.copyOf()), typedPersian)).isEqualTo(data)
        assertThrows(BackupCrypto.WrongPasswordException::class.java) {
            open(seal(data, typedArabic.copyOf()), "\u06A9\u0644\u06CC\u062F124".toCharArray())
        }
    }

    @Test
    fun aFileOfTheFirstVersionStillOpens() {
        val data = bytes(50)
        val file = seal(data)
        file[7] = '1'.code.toByte() // UMSGENC1: an ASCII password has the same key in both versions

        assertThat(open(file)).isEqualTo(data)
        assertThrows(BackupCrypto.WrongPasswordException::class.java) { open(file, "nope".toCharArray()) }
    }

    private companion object {
        const val TEST_ITERATIONS = 1_000
        const val TAG_SIZE = 16
        const val HEADER_SIZE = 8 + 4 + 16 + 8 + TAG_SIZE
    }
}
