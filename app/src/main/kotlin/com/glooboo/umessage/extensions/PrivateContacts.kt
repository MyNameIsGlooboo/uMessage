package com.glooboo.umessage.extensions

import android.content.Context
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.models.SimpleContact

/**
 * Contacts from Fossify's private contacts provider (only present when the Fossify Contacts app is installed).
 * Returns an empty list without querying when the provider is missing, so the log is not flooded with
 * "Failed to find provider info". Call off the main thread.
 */
fun Context.getPrivateContactsIfAvailable(): ArrayList<SimpleContact> {
    val authority = MyContactsContentProvider.CONTACTS_CONTENT_URI.authority.orEmpty()
    val available = runCatching { packageManager.resolveContentProvider(authority, 0) != null }.getOrDefault(false)
    if (!available) return ArrayList()
    return MyContactsContentProvider.getSimpleContacts(this, getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true))
}
