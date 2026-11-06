package com.glooboo.umessage.ui.onboarding

import android.app.Application
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.provider.Telephony
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.glooboo.umessage.R
import com.glooboo.umessage.activities.MainActivity
import com.glooboo.umessage.activities.SettingsActivity
import com.glooboo.umessage.helpers.Config
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.spam.UnknownSenderMode
import com.glooboo.umessage.ui.settings.SettingsPage
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.glooboo.umessage.ui.theme.applyUMessageWindow

/**
 * First-run flow: welcome, allow (default SMS app and permissions, skipped when all set), unknown-sender mode,
 * keep running (battery).
 * Also doubles as the trampoline for the notification "Copy code" action (see [ACTION_COPY_CODE]).
 */
class OnboardingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.action == ACTION_COPY_CODE) {
            copyCodeAndFinish()
            return
        }

        applyUMessageWindow()
        val fromSettings = intent?.getBooleanExtra(EXTRA_FROM_SETTINGS, false) == true
        setContent {
            UMessageTheme {
                val vm: OnboardingViewModel = viewModel()
                OnboardingScreen(
                    viewModel = vm,
                    reduceMotion = isMotionReduced(),
                    onFinish = { openBackup -> finishOnboarding(fromSettings, openBackup) },
                )
            }
        }
    }

    private fun isMotionReduced(): Boolean = try {
        Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    } catch (_: Exception) {
        false
    }

    private fun finishOnboarding(fromSettings: Boolean, openBackup: Boolean) {
        OnboardingState.setCompleted(this)
        setResult(RESULT_OK)
        if (!fromSettings) {
            startActivity(Intent(this, MainActivity::class.java))
        }
        if (openBackup) {
            startActivity(
                Intent(this, SettingsActivity::class.java)
                    .putExtra(SettingsActivity.EXTRA_PAGE, SettingsPage.Backup.name),
            )
        }
        finish()
    }

    private fun copyCodeAndFinish() {
        val code = intent?.getStringExtra(EXTRA_CODE).orEmpty()
        if (code.isNotEmpty()) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText(getString(R.string.onb_notif_clip_label), code)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                clip.description.extras = android.os.PersistableBundle().apply {
                    putBoolean("android.content.extra.IS_SENSITIVE", true)
                }
            }
            clipboard?.setPrimaryClip(clip)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(this, R.string.onb_notif_code_copied, Toast.LENGTH_SHORT).show()
            }
        }
        finish()
    }

    companion object {
        const val ACTION_COPY_CODE = "com.glooboo.spamless.action.COPY_CODE"
        const val EXTRA_CODE = "extra_code"

        /** Pass true when reopening from settings: finishing then returns instead of opening Main. */
        const val EXTRA_FROM_SETTINGS = "extra_from_settings"

        /** The role is authoritative on 29+; Telephony.Sms.getDefaultSmsPackage can return null on HyperOS. */
        fun isDefaultSmsApp(context: Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = context.getSystemService(RoleManager::class.java)
                if (roleManager != null && roleManager.isRoleHeld(RoleManager.ROLE_SMS)) return true
            }
            return Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        }

        /** Intent to ask for the SMS role, or null when the platform offers no dialog. */
        fun defaultSmsRequestIntent(context: Context): Intent? {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = context.getSystemService(RoleManager::class.java)
                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                    return roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
                }
                return null
            }
            return Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, context.packageName)
        }
    }
}

/** Holds the unknown-sender choice and how long blocked messages are kept; writes it through the frozen [UMessage]
 * repository. */
class OnboardingViewModel(app: Application) : AndroidViewModel(app) {
    private val _mode = MutableStateFlow(SpamSettings().mode)
    val mode: StateFlow<UnknownSenderMode> = _mode.asStateFlow()
    private val _purgeDays = MutableStateFlow(SpamSettings().purgeAfterDays)
    val purgeDays: StateFlow<Int> = _purgeDays.asStateFlow()
    private val config = Config.newInstance(app)
    private val _deliveryReports = MutableStateFlow(config.enableDeliveryReports)
    val deliveryReports: StateFlow<Boolean> = _deliveryReports.asStateFlow()

    init {
        viewModelScope.launch {
            // The engine may not be installed yet (or in previews); keep defaults then.
            runCatching { UMessage.repository(app).settings.first() }.onSuccess {
                _mode.value = it.mode
                _purgeDays.value = it.purgeAfterDays
            }
        }
    }

    fun setMode(mode: UnknownSenderMode) {
        _mode.value = mode
        persist()
    }

    /** Written to the preference at once, so rotation, process death and Settings all see the same value. */
    fun setDeliveryReports(on: Boolean) {
        config.enableDeliveryReports = on
        _deliveryReports.value = on
    }

    fun setPurgeDays(days: Int) {
        _purgeDays.value = days
        persist()
    }

    private fun persist() {
        val mode = _mode.value
        val days = _purgeDays.value
        viewModelScope.launch {
            runCatching {
                UMessage.repository(getApplication()).updateSettings {
                    it.copy(mode = mode, purgeAfterDays = days)
                }
            }
        }
    }
}
