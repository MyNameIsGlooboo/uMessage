package com.glooboo.umessage.helpers

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The optional password on an exported message file. The file is `UMSGENC1`, the key-derivation settings, then the
 * content in 64 KB chunks, each sealed with AES-GCM (so a huge file is never held in memory, which Android's GCM
 * would otherwise do). The key comes from the password with PBKDF2-HMAC-SHA256 and a random salt.
 *
 * Every chunk has its own nonce (a random prefix plus a counter) and carries a flag, authenticated with the chunk,
 * that says whether it is the last one: a file cut short, or with chunks removed or reordered, fails instead of
 * restoring part of the messages. A small first chunk with nothing in it tells a wrong password from a damaged file.
 *
 * The password is never stored; there is no way back without it.
 */
object BackupCrypto {

    /** The password does not open the file. */
    class WrongPasswordException : IOException("wrong password")

    /** The file is not a protected export, or it is cut short or changed. */
    class DamagedFileException(message: String) : IOException(message)

    const val ITERATIONS = 200_000
    const val CHUNK_SIZE = 64 * 1024

    private val MAGIC = "UMSGENC1".toByteArray(Charsets.US_ASCII)
    private const val SALT_SIZE = 16
    private const val NONCE_PREFIX_SIZE = 8
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128
    private const val TAG_SIZE = TAG_BITS / 8
    private const val KEY_BITS = 256
    private const val MAX_ITERATIONS = 5_000_000
    private const val INT_BYTES = 4
    private const val HEADER_SIZE = 8 + INT_BYTES + SALT_SIZE + NONCE_PREFIX_SIZE + TAG_SIZE
    private const val AAD_VERIFIER: Byte = 2
    private const val AAD_MORE: Byte = 0
    private const val AAD_LAST: Byte = 1
    private const val BYTE_MASK = 0xFF

    /** True when [stream] starts with the protected-file marker. Needs a stream that supports mark and reset. */
    fun isProtected(stream: InputStream): Boolean {
        require(stream.markSupported())
        stream.mark(MAGIC.size)
        return try {
            val head = ByteArray(MAGIC.size)
            readFully(stream, head, 0, head.size) == head.size && head.contentEquals(MAGIC)
        } finally {
            stream.reset()
        }
    }

    /** Wraps [output]: what is written comes out sealed. Close the returned stream to finish the file. */
    fun encrypt(output: OutputStream, password: CharArray, iterations: Int = ITERATIONS): OutputStream {
        require(password.isNotEmpty())
        val random = SecureRandom()
        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val noncePrefix = ByteArray(NONCE_PREFIX_SIZE).also(random::nextBytes)
        val key = deriveKey(password, salt, iterations)
        output.write(MAGIC)
        output.write(BackupInts.toBytes(iterations))
        output.write(salt)
        output.write(noncePrefix)
        output.write(seal(key, noncePrefix, 0, AAD_VERIFIER, ByteArray(0), 0))
        return EncryptingStream(output, key, noncePrefix)
    }

    /**
     * Wraps [input], which starts with the marker: reading gives the original content. Fails at once with
     * [WrongPasswordException] for a wrong password, or [DamagedFileException] when the start is not a valid header;
     * damage further on fails when that part is read.
     */
    fun decrypt(input: InputStream, password: CharArray): InputStream {
        val header = readHeader(input)
        var at = MAGIC.size
        val iterations = BackupInts.read(header, at)
        at += INT_BYTES
        val salt = header.copyOfRange(at, at + SALT_SIZE)
        at += SALT_SIZE
        val noncePrefix = header.copyOfRange(at, at + NONCE_PREFIX_SIZE)
        at += NONCE_PREFIX_SIZE
        val key = deriveKey(password, salt, iterations)
        try {
            unseal(key, noncePrefix, 0, AAD_VERIFIER, header, at, TAG_SIZE)
        } catch (_: GeneralSecurityException) {
            throw WrongPasswordException()
        }
        return DecryptingStream(input, key, noncePrefix)
    }

    /** Reads the header of [input]; [DamagedFileException] when it is short, has no marker or odd key settings. */
    private fun readHeader(input: InputStream): ByteArray {
        val header = ByteArray(HEADER_SIZE)
        val complete = readFully(input, header, 0, header.size) == header.size
        if (!complete || !header.copyOf(MAGIC.size).contentEquals(MAGIC)) {
            throw DamagedFileException("not a protected backup")
        }
        if (BackupInts.read(header, MAGIC.size) !in 1..MAX_ITERATIONS) throw DamagedFileException("bad key settings")
        return header
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        // The same text typed on another keyboard can differ in how accents are encoded; settle on one form.
        val normalized = Normalizer.normalize(String(password), Normalizer.Form.NFC).toCharArray()
        val spec = PBEKeySpec(normalized, salt, iterations, KEY_BITS)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
            normalized.fill(' ')
        }
    }

    private fun cipher(mode: Int, key: SecretKeySpec, noncePrefix: ByteArray, counter: Int, aad: Byte): Cipher {
        val iv = noncePrefix + BackupInts.toBytes(counter)
        check(iv.size == IV_SIZE)
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG_BITS, iv))
            updateAAD(byteArrayOf(aad))
        }
    }

    private fun seal(key: SecretKeySpec, noncePrefix: ByteArray, counter: Int, aad: Byte, data: ByteArray, size: Int) =
        cipher(Cipher.ENCRYPT_MODE, key, noncePrefix, counter, aad).doFinal(data, 0, size)

    private fun unseal(
        key: SecretKeySpec, noncePrefix: ByteArray, counter: Int, aad: Byte, data: ByteArray, offset: Int, size: Int,
    ): ByteArray = cipher(Cipher.DECRYPT_MODE, key, noncePrefix, counter, aad).doFinal(data, offset, size)

    /** Reads until [length] bytes or the end of the stream; returns how many were read. */
    private fun readFully(input: InputStream, buffer: ByteArray, offset: Int, length: Int): Int {
        var total = 0
        while (total < length) {
            val n = input.read(buffer, offset + total, length - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    private class EncryptingStream(
        private val output: OutputStream,
        private val key: SecretKeySpec,
        private val noncePrefix: ByteArray,
    ) : OutputStream() {
        private val buffer = ByteArray(CHUNK_SIZE)
        private var filled = 0
        private var counter = 1
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed) { "stream closed" }
            var pos = off
            var left = len
            while (left > 0) {
                // A full chunk waits for the next byte: the last chunk is the one that is not followed by more.
                if (filled == CHUNK_SIZE) writeChunk(last = false)
                val n = minOf(left, CHUNK_SIZE - filled)
                System.arraycopy(b, pos, buffer, filled, n)
                filled += n
                pos += n
                left -= n
            }
        }

        override fun flush() = output.flush()

        override fun close() {
            if (closed) return
            closed = true
            writeChunk(last = true)
            output.flush()
            output.close()
        }

        private fun writeChunk(last: Boolean) {
            output.write(seal(key, noncePrefix, counter++, if (last) AAD_LAST else AAD_MORE, buffer, filled))
            filled = 0
        }
    }

    private class DecryptingStream(
        input: InputStream,
        private val key: SecretKeySpec,
        private val noncePrefix: ByteArray,
    ) : InputStream() {
        private val input = PushbackInputStream(input, 1)
        private val sealed = ByteArray(CHUNK_SIZE + TAG_SIZE)
        private var plain = ByteArray(0)
        private var pos = 0
        private var counter = 1
        private var finished = false

        override fun read(): Int {
            if (!fill()) return -1
            return plain[pos++].toInt() and BYTE_MASK
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val n = minOf(len, plain.size - pos)
            System.arraycopy(plain, pos, b, off, n)
            pos += n
            return n
        }

        override fun available() = plain.size - pos

        override fun close() = input.close()

        private fun fill(): Boolean {
            while (pos >= plain.size) {
                if (finished) return false
                nextChunk()
            }
            return true
        }

        private fun nextChunk() {
            val n = readFully(input, sealed, 0, sealed.size)
            if (n < TAG_SIZE) throw DamagedFileException("the file ends too early")
            val last = n < sealed.size || peekEnd()
            plain = try {
                unseal(key, noncePrefix, counter, if (last) AAD_LAST else AAD_MORE, sealed, 0, n)
            } catch (_: GeneralSecurityException) {
                throw DamagedFileException("the file is damaged or cut short")
            }
            counter++
            pos = 0
            finished = last
        }

        private fun peekEnd(): Boolean {
            val next = input.read()
            if (next < 0) return true
            input.unread(next)
            return false
        }
    }
}
