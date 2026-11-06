package com.glooboo.umessage.helpers

/** Big-endian four-byte integers of the protected backup header and chunk nonces. */
internal object BackupInts {
    private const val INT_BYTES = 4
    private const val BYTE_BITS = 8
    private const val BYTE_MASK = 0xFF

    fun toBytes(value: Int): ByteArray =
        ByteArray(INT_BYTES) { (value ushr (BYTE_BITS * (INT_BYTES - 1 - it))).toByte() }

    fun read(bytes: ByteArray, at: Int): Int =
        (0 until INT_BYTES).fold(0) { acc, i -> (acc shl BYTE_BITS) or (bytes[at + i].toInt() and BYTE_MASK) }
}
