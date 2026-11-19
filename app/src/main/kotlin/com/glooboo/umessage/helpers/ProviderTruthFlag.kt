package com.glooboo.umessage.helpers

/**
 * The internal switch of the provider-truth work: while it is off, the app behaves as 0.4.14. It gates three things,
 * the new incoming path, the removal of the hidden wrote-to check and the migration of old Spam messages into the
 * system message store.
 *
 * It is a "stop going further" switch, not a way back. Turning it off again stops the new path and the migration from
 * doing more; it cannot undo the Room migrations, the settings mapping or spam messages already written. There is no
 * user setting for it: it is changed in code only, and it is removed in step J, once the new path is the only one.
 */
internal object ProviderTruthFlag {
    /** Off by default: nothing reads the new path until a later step turns this on. */
    const val ENABLED = false
}
