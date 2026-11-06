package com.glooboo.umessage.helpers

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import org.fossify.commons.helpers.isQPlus

/** Whether the app is the default SMS app, and how to ask for it when not. */
object DefaultSmsRole {

    sealed interface Status {
        /** The app is the default SMS app. */
        data object Held : Status

        /** Android has no SMS role to give (Android 10+ without one); the app cannot work. */
        data object Unavailable : Status

        /** Not the default: start [intent] for a result to ask the user. */
        class Request(val intent: Intent) : Status
    }

    fun status(context: Context): Status {
        if (isQPlus()) {
            val roleManager = context.getSystemService(RoleManager::class.java)
            return when {
                !roleManager!!.isRoleAvailable(RoleManager.ROLE_SMS) -> Status.Unavailable
                roleManager.isRoleHeld(RoleManager.ROLE_SMS) -> Status.Held
                else -> Status.Request(roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS))
            }
        }
        if (Telephony.Sms.getDefaultSmsPackage(context) == context.packageName) return Status.Held
        val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
        intent.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
        return Status.Request(intent)
    }
}
