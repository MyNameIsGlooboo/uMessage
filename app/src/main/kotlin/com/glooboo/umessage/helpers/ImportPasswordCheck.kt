package com.glooboo.umessage.helpers

import android.content.Context
import android.net.Uri

/** Looks at a backup file before the restore starts, so the dialog can ask for a password only when one is needed. */
object ImportPasswordCheck {

    /** True when the file at [uri] is a password-protected export. Run it off the main thread. */
    fun isProtected(context: Context, uri: Uri): Boolean = try {
        context.contentResolver.openInputStream(uri)?.buffered()?.use { BackupCrypto.isProtected(it) } == true
    } catch (_: Exception) {
        false
    }

    /**
     * Whether [password] opens the protected file at [uri]. Only the start of the file is read. A file that is
     * damaged rather than locked counts as open: the restore itself says what is wrong with it. Run it off the main
     * thread: working out the key takes a moment.
     */
    fun opens(context: Context, uri: Uri, password: CharArray): Boolean = try {
        context.contentResolver.openInputStream(uri)?.buffered()?.use { BackupCrypto.decrypt(it, password).close() }
        true
    } catch (_: BackupCrypto.WrongPasswordException) {
        false
    } catch (_: Exception) {
        true
    } finally {
        password.fill(' ')
    }
}
