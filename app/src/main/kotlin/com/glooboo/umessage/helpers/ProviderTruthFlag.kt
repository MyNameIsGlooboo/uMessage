package com.glooboo.umessage.helpers

import androidx.annotation.VisibleForTesting

/**
 * The internal switch of the provider-truth work: while it is off, the app behaves as 0.4.14. It gates three things,
 * the new incoming path, the removal of the hidden wrote-to check and the migration of old Spam messages into the
 * system message store.
 *
 * It is a "stop going further" switch, not a way back. Turning it off again stops the new path and the migration from
 * doing more; it cannot undo the Room migrations, the settings mapping or spam messages already written. There is no
 * user setting for it. The shipped value is [SHIPPED_DEFAULT] and is changed in code only; tests may set [enabled] to
 * run both paths, and must set it back. It is removed in step J, once the new path is the only one.
 */
internal object ProviderTruthFlag {
    /** Off: nothing reads the new path until a later step turns this on in code. */
    const val SHIPPED_DEFAULT = false

    /** Read on every check, so a test can switch it; only tests write it. */
    @Volatile
    var enabled: Boolean = SHIPPED_DEFAULT
        @VisibleForTesting set
}
