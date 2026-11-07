package com.glooboo.umessage.ui.thread

import android.net.Uri

/**
 * Which files another app may hand to uMessage as an attachment ("Share" or "Send to"). The app opens such a file
 * with its own rights, so a sender could name a file only uMessage can read (its database, its preferences) and
 * get it attached to a message. Refused: `file:` addresses, and content addresses of uMessage's own providers.
 */
object IncomingShareGuard {
    fun isAllowed(uri: Uri?, ownPackage: String): Boolean {
        if (uri == null) return false
        val scheme = uri.scheme?.lowercase()
        if (scheme != "content") return false
        val authority = uri.authority?.lowercase() ?: return false
        val own = ownPackage.lowercase()
        // Authorities of the app itself (the share and MMS file providers) and the old spamless id both start so.
        return !(authority == own || authority.startsWith("$own.") || authority.startsWith("com.glooboo.spamless"))
    }
}
