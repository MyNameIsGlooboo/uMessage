package com.glooboo.umessage.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class BackupType {
    @SerialName("sms")
    SMS,

    @SerialName("mms")
    MMS,

    @SerialName("umessage_notes")
    NOTES,

    @SerialName("umessage_appdata")
    APP_DATA,

    @SerialName("umessage_blocked")
    BLOCKED,
}
