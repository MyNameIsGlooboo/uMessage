package com.glooboo.umessage.dialogs

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.glooboo.umessage.R
import com.glooboo.umessage.helpers.ExportFormat

/**
 * What the user has chosen in the export dialog so far. Nothing here is remembered after the dialog closes, the
 * password least of all: it only travels to the backup service through memory.
 */
class ExportOptionsState(initialName: String, initialSms: Boolean, initialMms: Boolean) {
    var name by mutableStateOf(initialName)
    var format by mutableStateOf(ExportFormat.JSON)
    var sms by mutableStateOf(initialSms)
    var mms by mutableStateOf(initialMms)

    /** The conversations to export; null means all of them. */
    var chosen by mutableStateOf<List<Long>?>(null)
    var chooseConversations by mutableStateOf(false)
    /** I14: a message export holds full texts and numbers, so a password is asked for unless the user unticks it. */
    var protect by mutableStateOf(true)
    var password by mutableStateOf("")
    var confirm by mutableStateOf("")

    /** The string that says what is wrong with the password, or null when it is fine or not wanted. */
    @get:StringRes
    val passwordProblem: Int?
        get() = when {
            !protect -> null
            password.isEmpty() -> R.string.export_password_empty
            password != confirm -> R.string.export_password_mismatch
            else -> null
        }

    /** The name of the file to create: a protected file gets its own extension, whatever its content. */
    val fileName: String get() = if (protect) "$name.$PROTECTED_EXTENSION" else "$name.${format.extension}"

    val mimeType: String
        get() = when {
            protect -> "application/octet-stream"
            format == ExportFormat.SMS_XML -> "text/xml"
            else -> "application/json"
        }

    companion object {
        const val PROTECTED_EXTENSION = "umsg"
    }
}
