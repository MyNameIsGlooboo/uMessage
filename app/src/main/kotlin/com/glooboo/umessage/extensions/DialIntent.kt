package com.glooboo.umessage.extensions

import android.content.Intent
import android.net.Uri

private val dialableNumber = Regex("^[+*#]?[0-9 ().*#\\-]{1,40}$")

/** True when [phoneNumber] is something a phone can dial: digits with an optional plus and the usual separators. */
fun isDialableNumber(phoneNumber: String): Boolean = dialableNumber.matches(phoneNumber.trim())

/**
 * The intent behind a tap on a phone number. With the CALL_PHONE permission granted it places the call at once
 * (ACTION_CALL); without it the dialer opens with the number filled in (ACTION_DIAL). Returns null for text that is
 * not a dialable number, so a tap never hands anything else to the phone app.
 */
fun callIntent(phoneNumber: String, canCall: Boolean): Intent? {
    if (!isDialableNumber(phoneNumber)) return null
    val action = if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL
    return Intent(action).setData(Uri.fromParts("tel", phoneNumber.trim(), null))
}
