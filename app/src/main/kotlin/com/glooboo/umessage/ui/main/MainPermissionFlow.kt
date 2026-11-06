package com.glooboo.umessage.ui.main

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** What the screen does next in the default-SMS and SMS-permission flow. */
enum class PermissionStep {
    None,

    /** Ask to become the default SMS app (or go on to the permissions when it already is). */
    RequestRole,

    /** Ask for the SMS, contacts and notification permissions. */
    AskPermissions,

    /** Check again whether the user fixed things in Android settings. */
    Recheck,
}

/**
 * The decisions of the conversation list's default-SMS and permission flow. The screen asks Android and starts the
 * dialogs; this says which step comes next and what the screen shows (MC-04, MC-05, CM-05). The flags that pick the
 * screen survive rotation with the view model and a killed process through [SavedStateHandle].
 */
class MainPermissionFlow(private val handle: SavedStateHandle) : ViewModel() {

    /** The user declined the default-SMS dialog: the explanation screen replaces the list. */
    var needsDefaultApp by savedFlag(KEY_NEEDS_DEFAULT_APP)
        private set

    /** MC-04: SMS permission was denied; an explanation with Grant and Open settings replaces the list. */
    var missingSmsPermission by savedFlag(KEY_MISSING_SMS_PERMISSION)
        private set

    /** CM-05: another app took the default-SMS role while the list was showing; a banner offers to fix it. */
    var notDefaultSmsApp by mutableStateOf(false)
        private set

    /** Notifications were refused: a dialog offers the notification settings. */
    var showNotificationDialog by mutableStateOf(false)

    /** MC-05: the default-SMS dialog is open; its answer reaches whichever screen is showing then. */
    var roleRequestPending: Boolean
        get() = handle[KEY_ROLE_REQUEST_PENDING] ?: false
        private set(value) {
            handle[KEY_ROLE_REQUEST_PENDING] = value
        }

    /** One default-SMS dialog at a time. */
    val canRequestRole: Boolean get() = !roleRequestPending

    /** SMS permissions were granted and the list started loading. */
    private var listStarted = false

    /**
     * First step of a new screen. After rotation an open default-SMS dialog answers the new screen (asking again
     * stacked a second dialog), and an explanation screen stays put.
     */
    fun start(): PermissionStep {
        showNotificationDialog = false
        return when {
            roleRequestPending || needsDefaultApp -> PermissionStep.None
            missingSmsPermission -> PermissionStep.Recheck
            else -> PermissionStep.RequestRole
        }
    }

    /** On every resume: the user may have fixed the role or the permission in Android settings. */
    fun recheck(isDefaultSmsApp: Boolean, hasSmsPermissions: Boolean): PermissionStep = when {
        needsDefaultApp -> if (isDefaultSmsApp) {
            needsDefaultApp = false
            PermissionStep.RequestRole
        } else {
            PermissionStep.None
        }
        missingSmsPermission -> if (hasSmsPermissions) PermissionStep.AskPermissions else PermissionStep.None
        else -> PermissionStep.None
    }

    /** After [recheck]'s step ran: warn when the list is showing but another app has the role. */
    fun updateNotDefaultBanner(isDefaultSmsApp: Boolean) {
        notDefaultSmsApp = listStarted && !isDefaultSmsApp && !roleRequestPending && !needsDefaultApp
    }

    fun onRoleRequested() {
        roleRequestPending = true
    }

    /** The default-SMS dialog answered. Declining explains what is lost and offers a retry instead of closing. */
    fun onRoleResult(isDefaultSmsApp: Boolean): PermissionStep {
        roleRequestPending = false
        return if (isDefaultSmsApp) {
            needsDefaultApp = false
            notDefaultSmsApp = false
            PermissionStep.AskPermissions
        } else {
            needsDefaultApp = true
            PermissionStep.None
        }
    }

    /** MC-04: explain instead of closing the app. */
    fun onSmsPermissionDenied() {
        missingSmsPermission = true
    }

    fun onSmsPermissionsGranted() {
        missingSmsPermission = false
    }

    fun onNotificationPermission(granted: Boolean) {
        if (!granted) showNotificationDialog = true
    }

    /** True the first time only: a second pass (Grant again, or back from settings) must not start the list again. */
    fun startListOnce(): Boolean {
        if (listStarted) return false
        listStarted = true
        return true
    }

    private fun savedFlag(key: String) = object : ReadWriteProperty<Any?, Boolean> {
        private val state = mutableStateOf(handle[key] ?: false)

        override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = state.value

        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            state.value = value
            handle[key] = value
        }
    }

    internal companion object {
        const val KEY_ROLE_REQUEST_PENDING = "role_request_pending"
        const val KEY_NEEDS_DEFAULT_APP = "needs_default_app"
        const val KEY_MISSING_SMS_PERMISSION = "missing_sms_permission"
    }
}
