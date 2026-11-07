package com.glooboo.umessage.spam.engine

import com.glooboo.umessage.spam.DecisionReason

/**
 * I11: when a message that looks like a verification code is held only because it carries a link (Smart mode does
 * not let a code with a link through, as phishing copies that shape), the person waiting for the code gets a quiet
 * notice, without sender or text. Pure JVM; the notification is shown by `BlockedNotifier`.
 */
object HeldCodeNotice {

    /** True for a held message whose only fault is a link next to a code. */
    fun applies(reason: DecisionReason, body: String): Boolean {
        if (reason != DecisionReason.UNKNOWN_SENDER && reason != DecisionReason.FOREIGN_NUMBER) return false
        return OtpDetector.looksLikeOtp(body) && OtpDetector.containsUrl(body) && !OtpDetector.isOriginBound(body)
    }
}
