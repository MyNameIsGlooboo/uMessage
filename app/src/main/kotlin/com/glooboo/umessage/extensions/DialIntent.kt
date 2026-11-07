package com.glooboo.umessage.extensions

import android.content.Intent
import android.net.Uri

/**
 * The intent behind a tap on a phone number: it opens the dialer with the number filled in and the user presses the
 * call button. Never ACTION_CALL, which would place the call at once (one stray tap on a scam number could dial a
 * premium line) and would need the CALL_PHONE permission, which the app therefore no longer declares.
 */
fun dialIntent(phoneNumber: String): Intent =
    Intent(Intent.ACTION_DIAL).setData(Uri.fromParts("tel", phoneNumber, null))
