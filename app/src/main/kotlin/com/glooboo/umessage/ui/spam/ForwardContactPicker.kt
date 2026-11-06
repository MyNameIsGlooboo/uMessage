package com.glooboo.umessage.ui.spam

import android.content.Context
import android.content.Intent
import android.provider.ContactsContract

/**
 * Picks one phone number with the system contact picker. The picker grants access to just the chosen number, so this
 * needs no contacts permission. Launch [intent] for a result, then read the number with [numberOf].
 */
object ForwardContactPicker {
    fun intent(): Intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)

    /** The number behind the row the picker returned in [data], or null when nothing usable came back. */
    fun numberOf(context: Context, data: Intent?): String? {
        val uri = data?.data ?: return null
        return runCatching {
            context.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
    }
}
