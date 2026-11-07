package com.glooboo.umessage.ui.spam

import android.content.Context
import com.glooboo.umessage.spam.engine.NumberNormalizer
import com.glooboo.umessage.spam.engine.defaultRegion
import java.util.concurrent.ConcurrentHashMap

/**
 * I20: which blocked messages belong to one sender row. "+447700900123", "07700900123" and "00447700900123" are one
 * number, so they are one row (and one chip count) instead of three; a sender name is compared without regard to case.
 * A value that is not a readable number keeps its own text as the key.
 */
class SenderKeys(private val normalizer: NumberNormalizer) {
    private val memo = ConcurrentHashMap<String, String>()

    fun keyOf(address: String): String = memo.getOrPut(address) {
        runCatching { normalizer.comparableKey(address) }.getOrDefault("").ifEmpty { address }
    }

    companion object {
        /** Numbers are read in the country of the phone, like everywhere else in the spam filter. */
        fun forDevice(context: Context): SenderKeys {
            val app = context.applicationContext
            return SenderKeys(NumberNormalizer { defaultRegion(app) })
        }
    }
}
