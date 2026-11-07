package org.fossify.commons.helpers

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Storage format and brute-force limits for the app lock (uMessage patch).
 *
 * The PIN and pattern screens still compute the old "entry hash": lowercase hex SHA-1 of the PIN, or of the pattern
 * cells. That value is never stored any more. Stored formats, newest first:
 * - `pbkdf2-sha256-device$<iterations>$<salt hex>$<mac hex>`: a salted PBKDF2 of the entry hash, then an HMAC with a
 *   key kept in the Android Keystore ([deviceBinding]). The key never leaves the phone, so a copied settings file
 *   cannot be guessed against anywhere else: every guess has to go through this phone and its wait rules.
 * - `pbkdf2-sha256$<iterations>$<salt hex>$<key hex>`: the same without the Keystore step. Written when the Keystore
 *   does not work on a phone, and by older versions.
 * - a bare 40 character hex string: the old unsalted SHA-1.
 *
 * Every older format still verifies, and [upgrade] replaces it with the newest after the next correct entry, so an
 * existing PIN or pattern keeps working through every update. App data (including the settings and the Keystore key)
 * is excluded from backups and device transfer, so the key and the value it protects always travel together.
 *
 * Wrong entries are counted in the shared config and never reset by waiting, only by a correct entry. From the
 * [MAX_PASSWORD_RETRY_COUNT]th wrong entry on, each one starts a wait that doubles every time
 * ([DEFAULT_PASSWORD_COUNTDOWN] seconds, then twice that, up to [MAX_LOCKOUT_SECONDS]).
 */
object AppLockSecurity {
    private const val PREFIX = "pbkdf2-sha256"
    private const val DEVICE_PREFIX = "pbkdf2-sha256-device"
    private const val ITERATIONS = 100_000
    private const val SALT_BYTES = 16
    private const val KEY_BITS = 256
    const val MAX_LOCKOUT_SECONDS = 60 * 60

    /** A keyed hash with a key that cannot leave this phone; null when no such key can be used. */
    fun interface DeviceBinding {
        fun mac(data: ByteArray): ByteArray?
    }

    /** The binding in use: the Android Keystore. Tests replace it (plain JVMs have no Keystore and get null). */
    @Volatile
    var deviceBinding: DeviceBinding = KeystoreBinding

    private val random by lazy { SecureRandom() }

    /** True for the old unsalted SHA-1 format. */
    fun isLegacy(stored: String): Boolean = stored.length == 40 && stored.all { it in '0'..'9' || it in 'a'..'f' }

    /** A new stored value for [entryHash], with a fresh random salt, bound to this phone when the Keystore works. */
    fun create(entryHash: String): String {
        val salt = ByteArray(SALT_BYTES).also { random.nextBytes(it) }
        val key = derive(entryHash, salt, ITERATIONS)
        val bound = deviceBinding.macOrNull(key)
        return if (bound != null) format(DEVICE_PREFIX, ITERATIONS, salt, bound) else format(PREFIX, ITERATIONS, salt, key)
    }

    /** True when [entryHash] (what the user just entered) matches [stored]. */
    fun verify(stored: String, entryHash: String): Boolean {
        if (stored.isEmpty() || entryHash.isEmpty()) return false
        if (isLegacy(stored)) return constantTimeEquals(stored.toByteArray(), entryHash.toByteArray())
        val parts = stored.split('$')
        if (parts.size != 4 || (parts[0] != PREFIX && parts[0] != DEVICE_PREFIX)) return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return false
        val salt = parts[2].hexToBytesOrNull() ?: return false
        val expected = parts[3].hexToBytesOrNull() ?: return false
        val key = derive(entryHash, salt, iterations)
        val actual = if (parts[0] == DEVICE_PREFIX) deviceBinding.macOrNull(key) ?: return false else key
        return constantTimeEquals(expected, actual)
    }

    /**
     * The value to keep after a correct [entryHash]: [stored] itself when it is already the newest format, otherwise
     * a fresh value from [create] (which is only bound to the phone when the Keystore works, so nothing is lost when
     * it does not).
     */
    fun upgrade(stored: String, entryHash: String): String {
        if (stored.startsWith("$DEVICE_PREFIX$")) return stored
        val fresh = create(entryHash)
        return if (isLegacy(stored) || fresh.startsWith("$DEVICE_PREFIX$")) fresh else stored
    }

    /** Length of the wait that the [failures]th consecutive wrong entry starts. */
    fun lockoutSeconds(failures: Int): Int {
        if (failures < MAX_PASSWORD_RETRY_COUNT) return 0
        val doublings = (failures - MAX_PASSWORD_RETRY_COUNT).coerceAtMost(20)
        return (DEFAULT_PASSWORD_COUNTDOWN.toLong() shl doublings).coerceAtMost(MAX_LOCKOUT_SECONDS.toLong()).toInt()
    }

    /**
     * Seconds still to wait, given [failures] consecutive wrong entries, the last of which was at [lastFailureMs].
     * A clock that went backwards counts as no time passed. [monotonicElapsedMs] is the time since the failure on the
     * phone's own running clock (SystemClock.elapsedRealtime, which setting the date and time cannot move), or -1
     * when it is not known (after a restart, old data): moving the wall clock forward then cannot cut the wait short.
     */
    fun remainingSeconds(failures: Int, lastFailureMs: Long, nowMs: Long, monotonicElapsedMs: Long = -1L): Int {
        val total = lockoutSeconds(failures)
        if (total == 0 || lastFailureMs <= 0L) return 0
        var elapsed = (nowMs - lastFailureMs).coerceAtLeast(0L)
        if (monotonicElapsedMs >= 0L) elapsed = minOf(elapsed, monotonicElapsedMs)
        val remainingMs = total * 1000L - elapsed
        return if (remainingMs > 0) ((remainingMs + 999) / 1000).toInt() else 0
    }

    private fun derive(entryHash: String, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(entryHash.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun DeviceBinding.macOrNull(data: ByteArray): ByteArray? = runCatching { mac(data) }.getOrNull()

    private fun format(prefix: String, iterations: Int, salt: ByteArray, key: ByteArray) =
        "$prefix$$iterations$${salt.toHex()}$${key.toHex()}"

    private fun constantTimeEquals(a: ByteArray, b: ByteArray) = MessageDigest.isEqual(a, b)

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytesOrNull(): ByteArray? {
        if (length % 2 != 0 || isEmpty()) return null
        return ByteArray(length / 2) { i ->
            val hi = Character.digit(this[i * 2], 16)
            val lo = Character.digit(this[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            ((hi shl 4) or lo).toByte()
        }
    }

    /**
     * HMAC-SHA256 with a non-exportable Keystore key, made on first use. It needs no user authentication, so changing
     * the screen lock or fingerprints does not invalidate it; it is deleted only with the app's data.
     */
    private object KeystoreBinding : DeviceBinding {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "spamless_app_lock_hmac"

        @Synchronized
        override fun mac(data: ByteArray): ByteArray? = runCatching {
            Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256).run {
                init(key())
                doFinal(data)
            }
        }.getOrNull()

        private fun key(): SecretKey {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE).run {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN).build())
                generateKey()
            }
        }
    }
}
