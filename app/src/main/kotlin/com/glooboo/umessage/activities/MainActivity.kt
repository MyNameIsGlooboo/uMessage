package com.glooboo.umessage.activities

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.fossify.commons.extensions.appLaunched
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.openNotificationSettings
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.PERMISSION_READ_SMS
import org.fossify.commons.helpers.PERMISSION_SEND_SMS
import com.glooboo.umessage.BuildConfig
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.checkAndDeleteOldRecycleBinMessages
import com.glooboo.umessage.extensions.clearAllMessagesIfNeeded
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.helpers.DefaultSmsRole
import com.glooboo.umessage.helpers.MainTabLinks
import com.glooboo.umessage.helpers.NewConversationShortcut
import com.glooboo.umessage.ui.components.PermissionRequiredDialog
import com.glooboo.umessage.ui.conversations.ConversationsViewModel
import com.glooboo.umessage.ui.main.MainPermissionFlow
import com.glooboo.umessage.ui.main.MainScreen
import com.glooboo.umessage.ui.main.DefaultSmsRequiredScreen
import com.glooboo.umessage.ui.main.NotDefaultSmsBanner
import com.glooboo.umessage.ui.main.SmsPermissionRequiredScreen
import com.glooboo.umessage.ui.onboarding.OnboardingActivity
import com.glooboo.umessage.ui.onboarding.OnboardingState
import com.glooboo.umessage.ui.main.MainSearchViewModel
import com.glooboo.umessage.ui.main.PermissionStep
import com.glooboo.umessage.ui.main.keepSplash

/**
 * Hosts the conversation list. [MainPermissionFlow] decides the default-SMS and permission steps, [MainSearchViewModel]
 * runs search, [ConversationsViewModel] keeps the list in sync and [MainRoutes] opens other screens. This class starts
 * the Android dialogs those need, receives their answers and sets the Compose content.
 */
class MainActivity : ComposeSimpleActivity() {

    private val MAKE_DEFAULT_APP_REQUEST = 1

    private var resumedBefore = false

    /** A tab another screen asked for (Settings rows), consumed by the main screen. */
    private var openTab by mutableStateOf<String?>(null)

    private val conversationsViewModel: ConversationsViewModel by viewModels()
    private val searchViewModel: MainSearchViewModel by viewModels()
    private val permissionFlow: MainPermissionFlow by viewModels()
    private val routes = MainRoutes(this)

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        if (!OnboardingState.isCompleted(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        if (savedInstanceState == null) openTab = intent?.getStringExtra(MainTabLinks.EXTRA_OPEN_TAB)
        val splashSince = SystemClock.uptimeMillis()
        splash.setKeepOnScreenCondition {
            keepSplash(conversationsViewModel.state.value.loading, SystemClock.uptimeMillis() - splashSince)
        }
        appLaunched(BuildConfig.APPLICATION_ID)
        setupContent()

        checkAndDeleteOldRecycleBinMessages()
        clearAllMessagesIfNeeded {
            perform(permissionFlow.start())
        }
    }

    override fun onResume() {
        super.onResume()
        // MC-19: chats with newer saved messages move up, from the saved list only (no thread is read in full).
        if (resumedBefore) conversationsViewModel.syncResumed() else resumedBefore = true
        recheckPermissionFlow()

        conversationsViewModel.refresh()
        NewConversationShortcut.updateIfNeeded(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(MainTabLinks.EXTRA_OPEN_TAB)?.let { openTab = it }
    }

    override fun onStop() {
        super.onStop()
        // Close any open undo window so a pending delete is not lost when the app goes away. Not on rotation: the
        // view model survives it and the new screen offers the Undo again.
        if (!isChangingConfigurations) conversationsViewModel.flushPendingDeletes()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        super.onActivityResult(requestCode, resultCode, resultData)
        if (requestCode == MAKE_DEFAULT_APP_REQUEST) {
            perform(permissionFlow.onRoleResult(resultCode == RESULT_OK || OnboardingActivity.isDefaultSmsApp(this)))
        }
    }

    private fun perform(step: PermissionStep) {
        when (step) {
            PermissionStep.None -> Unit
            PermissionStep.RequestRole -> requestDefaultSmsRole()
            PermissionStep.AskPermissions -> askPermissions()
            PermissionStep.Recheck -> recheckPermissionFlow()
        }
    }

    /** On every resume: the user may have fixed the role or the permission in Android settings, or lost the role. */
    private fun recheckPermissionFlow() {
        val isDefault = OnboardingActivity.isDefaultSmsApp(this)
        val hasSmsPermissions = hasPermission(PERMISSION_READ_SMS) && hasPermission(PERMISSION_SEND_SMS)
        perform(permissionFlow.recheck(isDefault, hasSmsPermissions))
        permissionFlow.updateNotDefaultBanner(isDefault)
    }

    private fun requestDefaultSmsRole() {
        // One default-SMS dialog at a time; its answer arrives in onActivityResult.
        if (!permissionFlow.canRequestRole) return
        when (val role = DefaultSmsRole.status(this)) {
            DefaultSmsRole.Status.Held -> askPermissions()
            DefaultSmsRole.Status.Unavailable -> {
                toast(org.fossify.commons.R.string.unknown_error_occurred)
                finish()
            }
            is DefaultSmsRole.Status.Request -> {
                permissionFlow.onRoleRequested()
                startActivityForResult(role.intent, MAKE_DEFAULT_APP_REQUEST)
            }
        }
    }

    // SEND_SMS and READ_SMS are required; without READ_CONTACTS some chats just show a number instead of a name.
    private fun askPermissions() {
        handlePermission(PERMISSION_READ_SMS) { readSms ->
            if (!readSms) return@handlePermission permissionFlow.onSmsPermissionDenied()
            handlePermission(PERMISSION_SEND_SMS) { sendSms ->
                if (!sendSms) return@handlePermission permissionFlow.onSmsPermissionDenied()
                permissionFlow.onSmsPermissionsGranted()
                handlePermission(PERMISSION_READ_CONTACTS) {
                    handleNotificationPermission(permissionFlow::onNotificationPermission)
                    // MC-02: once per view model, which then also listens for refresh events. A rotated screen keeps
                    // its list.
                    if (permissionFlow.startListOnce()) conversationsViewModel.startSync()
                }
            }
        }
    }

    // ---- Compose content ------------------------------------------------------------------------------

    private fun setupContent() {
        val vm = conversationsViewModel
        val callbacks = routes.conversationsCallbacks(vm)
        val navigation = routes.navigation()
        val flow = permissionFlow
        setUMessageContent {
            when {
                flow.needsDefaultApp -> DefaultSmsRequiredScreen(
                    onSetDefault = ::requestDefaultSmsRole,
                    onClose = ::finish,
                )
                flow.missingSmsPermission -> SmsPermissionRequiredScreen(
                    onGrant = ::askPermissions,
                    onOpenSettings = routes::openAppSettings,
                    onClose = ::finish,
                )
                else -> {
                    MainScreen(
                        viewModel = vm,
                        callbacks = callbacks,
                        search = searchViewModel.state,
                        navigation = navigation,
                        archiveAvailable = config.isArchiveAvailable,
                        openTab = openTab,
                        onOpenTabHandled = { openTab = null },
                        onQueryChange = searchViewModel::onQueryChange,
                        onOpenResult = routes::openSearchResult,
                        banner = if (flow.notDefaultSmsApp) {
                            { NotDefaultSmsBanner(onFix = ::requestDefaultSmsRole) }
                        } else {
                            null
                        },
                    )
                    if (flow.showNotificationDialog) {
                        PermissionRequiredDialog(
                            text = getString(R.string.main_permission_notifications),
                            onGrant = {
                                flow.showNotificationDialog = false
                                openNotificationSettings()
                            },
                            onCancel = { flow.showNotificationDialog = false },
                        )
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.state.map { it.selecting }.distinctUntilChanged().collect { if (it) hideKeyboard() }
                }
                launch { vm.effects.collect(routes::handle) }
            }
        }
    }
}
