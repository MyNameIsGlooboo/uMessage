package com.glooboo.umessage.dialogs

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** What the restore dialog is showing and asking, kept in one place so the dialog's steps stay small. */
class ImportFormState(sms: Boolean, mms: Boolean) {
    var sms by mutableStateOf(sms)
    var mms by mutableStateOf(mms)
    var started by mutableStateOf(false)
    var protectedFile by mutableStateOf(false)
    var password by mutableStateOf("")
    var wrongPassword by mutableStateOf(false)
    var checking by mutableStateOf(false)
}
