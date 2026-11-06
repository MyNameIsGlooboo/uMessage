package com.glooboo.umessage.extensions

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import android.text.TextUtils
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import com.glooboo.umessage.helpers.avatarBitmap
import com.glooboo.umessage.helpers.stripControlCharacters
import org.fossify.commons.models.SimpleContact
import androidx.core.net.toUri

fun ArrayList<SimpleContact>.getThreadTitle(): String {
    // A thread created from a broken link can carry a NUL in its address; never show it.
    return joinGroupNames(map { it.name.stripControlCharacters() })
}

/**
 * A conversation's title from its people's names: all of them for up to [shown] people, else the first [shown]
 * and "+N" for the rest ("Sara, Ali, Reza +2"), so a big group still fits the top bar and the list (MC-13).
 */
fun joinGroupNames(names: List<String>, shown: Int = 3): String =
    if (names.size <= shown) {
        TextUtils.join(", ", names.toTypedArray()).orEmpty()
    } else {
        TextUtils.join(", ", names.take(shown).toTypedArray()) + " +" + (names.size - shown)
    }

fun ArrayList<SimpleContact>.getAddresses(): List<String> {
    return flatMap { it.phoneNumbers }.map { it.normalizedNumber }
}

fun SimpleContact.toPerson(context: Context? = null): Person {
    val uri =
        Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_LOOKUP_URI, contactId.toString())
    val iconCompat = if (context != null) {
        loadIcon(context)
    } else {
        IconCompat.createWithContentUri(photoUri)
    }

    return Person.Builder()
        .setName(name)
        .setUri(uri.toString())
        .setIcon(iconCompat)
        .setKey(uri.toString())
        .build()
}

fun SimpleContact.loadIcon(context: Context): IconCompat {
    try {
        val stream = context.contentResolver.openInputStream(photoUri.toUri())
        val bitmap = BitmapFactory.decodeStream(stream)
        stream?.close()
        val iconCompat = IconCompat.createWithAdaptiveBitmap(bitmap)
        return iconCompat
    } catch (e: Exception) {
        // No photo: the app's own avatar (initials, or the silhouette for a bare number), as in the list.
        return IconCompat.createWithAdaptiveBitmap(avatarBitmap(context, name, adaptive = true))
    }
}

