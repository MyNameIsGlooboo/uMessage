package com.glooboo.umessage.activities

import com.glooboo.umessage.ui.components.uiLocale
import com.glooboo.umessage.ui.components.localizeDigits
import com.glooboo.umessage.helpers.DEFAULT_SIM_PHONE
import com.glooboo.umessage.helpers.DEFAULT_SIM_ASK
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.combine
import com.glooboo.umessage.dialogs.showAppLockDialog
import org.fossify.commons.extensions.getBlockedNumbers
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.PROTECTION_FINGERPRINT
import org.fossify.commons.helpers.REQUEST_APP_UNLOCK
import org.fossify.commons.activities.maybeLaunchAppUnlockActivity
import org.fossify.commons.extensions.appLockManager
import com.glooboo.umessage.AppLockGuard
import com.glooboo.umessage.ui.lock.AppLockAdvice
import com.glooboo.umessage.ui.settings.FeedbackLinks
import com.glooboo.umessage.ui.lock.AppLockTimeouts
import com.glooboo.umessage.ui.lock.lockSetupMessage
import org.fossify.commons.helpers.SHOW_ALL_TABS
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.models.RadioItem
import com.glooboo.umessage.BuildConfig
import com.glooboo.umessage.R
import com.glooboo.umessage.dialogs.ExportMessagesDialog
import com.glooboo.umessage.export.CreateExportDocument
import com.glooboo.umessage.dialogs.ImportMessagesDialog
import com.glooboo.umessage.ui.components.ChoiceDialog
import com.glooboo.umessage.ui.components.ConfirmDialog
import com.glooboo.umessage.ui.components.InfoDialog
import com.glooboo.umessage.ui.components.showComposeDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.extensions.emptyMessagesRecycleBin
import com.glooboo.umessage.extensions.messagesDB
import com.glooboo.umessage.extensions.conversationsDB
import com.glooboo.umessage.helpers.MainTabLinks
import com.glooboo.umessage.helpers.navPrefs
import com.glooboo.umessage.helpers.threadPrefs
import com.glooboo.umessage.helpers.w3aPrefs
import com.glooboo.umessage.backup.SettingsBackup
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import android.widget.Toast
import com.glooboo.umessage.spam.engine.SpamEngine
import android.content.ClipData
import android.content.ClipboardManager
import com.glooboo.umessage.helpers.FILE_SIZE_100_KB
import com.glooboo.umessage.helpers.FILE_SIZE_1_MB
import com.glooboo.umessage.helpers.FILE_SIZE_200_KB
import com.glooboo.umessage.helpers.FILE_SIZE_2_MB
import com.glooboo.umessage.helpers.FILE_SIZE_300_KB
import com.glooboo.umessage.helpers.FILE_SIZE_600_KB
import com.glooboo.umessage.helpers.FILE_SIZE_NONE
import com.glooboo.umessage.helpers.LOCK_SCREEN_NOTHING
import com.glooboo.umessage.helpers.LOCK_SCREEN_SENDER
import com.glooboo.umessage.helpers.LOCK_SCREEN_SENDER_MESSAGE
import com.glooboo.umessage.helpers.LockScreenSystemSetting
import com.glooboo.umessage.helpers.BackupJob
import com.glooboo.umessage.helpers.refreshConversations
import com.glooboo.umessage.ui.settings.SettingsActions
import com.glooboo.umessage.ui.settings.SettingsPage
import com.glooboo.umessage.ui.settings.SettingsScreen
import com.glooboo.umessage.ui.settings.SettingsUiState
import com.glooboo.umessage.ui.settings.SimCards
import com.glooboo.umessage.ui.settings.SimMessagesDialog
import com.glooboo.umessage.ui.settings.SpamSummary
import com.glooboo.umessage.ui.log.DecisionLogActivity
import com.glooboo.umessage.ui.spam.BlockedMessagesActivity
import com.glooboo.umessage.ui.spam.BlockedNumbersActivity
import com.glooboo.umessage.spam.ForwardingAvailability
import com.glooboo.umessage.ui.spam.ForwardGatePolicy
import com.glooboo.umessage.ui.spam.ForwardingActivity
import com.glooboo.umessage.ui.spam.RulesActivity
import com.glooboo.umessage.ui.spam.SpamRepositoryProvider
import com.glooboo.umessage.ui.spam.SpamSettingsActivity
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.glooboo.umessage.ui.theme.ThemePresetsActivity
import android.provider.Settings
import com.glooboo.umessage.helpers.ensureCodeChannel
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.ui.lock.LockActivity
import com.glooboo.umessage.ui.lock.LockScope
import com.glooboo.umessage.ui.lock.shortLabelRes
import com.glooboo.umessage.ui.lock.showLockScopeDialog
import com.glooboo.umessage.helpers.HiddenChatsSession
import com.glooboo.umessage.ui.settings.AppLanguage
import com.glooboo.umessage.ui.settings.LanguageChoiceDialog
import com.glooboo.umessage.ui.settings.RestoreSummary

/**
 * Compose-hosted settings. Every option writes the same Config keys as the former View screen.
 * Stays a [SimpleActivity] because the commons security dialog needs one.
 */
class SettingsActivity : ComposeSimpleActivity() {
    private var blockedNumbersAtPause = -1
    private var recycleBinMessages = 0
    private var forwardRuleCount = 0
    private var forwardRulesOn = 0
    private var archivedCount = 0
    private var uiState by mutableStateOf(SettingsUiState())
    private var spamSummary by mutableStateOf<SpamSummary?>(null)

    private val messageImportFileTypes = buildList {
        add("application/json")
        add("application/xml")
        add("text/xml")
        add("text/plain") // XML backups saved as .txt; the importer reads the content, not the name
        add("application/octet-stream") // password-protected files
    }

    private val getContent =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                ImportMessagesDialog(this, uri)
            }
        }

    private var exportMessagesDialog: ExportMessagesDialog? = null

    private val saveDocument =
        registerForActivityResult(CreateExportDocument()) { uri ->
            if (uri != null) {
                exportMessagesDialog?.exportMessages(uri)
            }
        }

    private val saveSettingsBackup =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            if (uri != null) backUpSettings(uri)
        }

    private val openSettingsBackup =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) previewRestore(uri)
        }

    /** H1: with the app lock on, the owner confirms with it before a restore can add forward rules. */
    private val confirmLockForRestore =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) pickRestoreFile()
        }

    /** Resuming forwarding: the switch moves only once the app lock confirmed it is the owner. */
    private val confirmLockForResume =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                navPrefs.forwardPaused = false
                refreshState()
            }
        }

    /** I44: the lock scope dialog opens once the app lock confirmed it is the owner. */
    private val confirmLockForScope =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) showLockScopeDialog(::refreshState)
        }

    private val phoneStatePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshState() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshState()
        // Bug 7: another screen can open a page directly (the forward editor opens App lock).
        val initialPage = (intent?.getStringExtra(EXTRA_PAGE)
            ?.let { name -> SettingsPage.entries.firstOrNull { it.name == name } } ?: SettingsPage.Main)
            // The Google Play build has no Forwarding page, whoever asks for it.
            .takeUnless { it == SettingsPage.Forwarding && !uiState.forwardingAvailable } ?: SettingsPage.Main
        setContent {
            LaunchedEffect(Unit) { collectSpamSummary() }
            UMessageTheme {
                SettingsScreen(state = uiState.copy(spam = spamSummary), actions = actions, initialPage = initialPage)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshState()
        loadRecycleBinCount()
        loadForwardingAndArchived()
        reportInterruptedBackup()

        if (
            blockedNumbersAtPause != -1 && blockedNumbersAtPause != getBlockedNumbers().hashCode()
        ) {
            refreshConversations()
        }
    }

    override fun onPause() {
        super.onPause()
        blockedNumbersAtPause = getBlockedNumbers().hashCode()
    }

    // region State

    /** Keeps [spamSummary] null when the engine is not installed (release builds without it). */
    private suspend fun collectSpamSummary() {
        val repository = runCatching { SpamRepositoryProvider.get(this) }.getOrNull() ?: return
        runCatching {
            repository.settings.combine(repository.stats) { settings, stats ->
                SpamSummary(
                    mode = settings.mode,
                    blockedLast7Days = stats.blockedLast7Days,
                    blockedTotal = stats.blockedTotal,
                    restoredTotal = stats.restoredTotal,
                    notifyOnBlock = settings.notifyOnBlock,
                )
            }.collect { spamSummary = it }
        }
    }

    private fun refreshState() {
        uiState = SettingsUiState(
            simPermissionGranted = SimCards.hasPermission(this),
            showOldBlockedWords = config.blockedKeywords.isNotEmpty(),
            sims = SimCards.load(this),
            defaultSimId = config.defaultSimId,
            lockScreenLabel = lockScreenLabel(),
            forwardingAvailable = ForwardingAvailability.isAvailable(this),
            showLanguage = true,
            languageName = getString(AppLanguage.current(this).label),
            showCharacterCounter = config.showCharacterCounter,
            useSimpleCharacters = config.useSimpleCharacters,
            sendOnEnter = config.sendOnEnter,
            deliveryReports = config.enableDeliveryReports,
            sendLongAsMms = config.sendLongMessageMMS,
            sendGroupAsMms = config.sendGroupMessageMMS,
            mmsLimitLabel = mmsFileLimitText(),
            keepArchived = config.keepConversationsArchived,
            useJalaliDates = threadPrefs.useJalaliDates,
            useRecycleBin = config.useRecycleBin,
            recycleBinLabel = recycleBinLabel(),
            appLock = config.isAppPasswordProtectionOn,
            appLockTimeoutLabel = getString(timeoutLabel(AppLockTimeouts.closest(config.unlockTimeoutDurationMs))),
            appLockScopeLabel = getString(LockScope.current(this).shortLabelRes()),
            appLockBlockScreenshots = config.appLockBlockScreenshots,
            appLockAdvice = AppLockAdvice.of(
                config.isAppPasswordProtectionOn,
                config.appProtectionType,
                config.appPasswordHash,
                config.appLockCredentialWeak,
            ),
            showDecisionLog = decisionLogAvailable(),
            versionName = BuildConfig.VERSION_NAME.substringBefore('-'),
            fullVersionName = BuildConfig.VERSION_NAME,
            showRateRow = resources.getBoolean(R.bool.is_google_play_build),
            forwardRuleCount = forwardRuleCount,
            forwardRulesOn = forwardRulesOn,
            showForwardedTab = navPrefs.showForwardedTab == true,
            pauseAllForwarding = navPrefs.forwardPaused,
            maskForwardedCodes = w3aPrefs.maskForwardedCodes,
            maskKeepLastDigits = w3aPrefs.maskKeepLastDigits,
            dailyForwardLimit = w3aPrefs.dailyForwardLimit,
            dailyForwardLimitLabel = dailyLimitLabel(w3aPrefs.dailyForwardLimit),
            hideCodesOnLockScreen = w3aPrefs.hideCodesOnLockScreen,
            archivedCount = archivedCount,
        )
    }

    private fun decisionLogAvailable() =
        runCatching { Class.forName("com.glooboo.umessage.spam.SpamLog") }.isSuccess

    private fun recycleBinLabel() = resources.getQuantityString(
        R.plurals.delete_messages, recycleBinMessages, recycleBinMessages
    )

    /**
     * Counts forward rules, all and switched on (the Forwarding row's summary), and archived conversations, off the
     * main thread.
     */
    private fun loadForwardingAndArchived() {
        ensureBackgroundThread {
            val rules = runCatching { SpamEngine.forwardRepository(this).currentRules() }.getOrDefault(emptyList())
            forwardRuleCount = rules.size
            forwardRulesOn = rules.count { it.enabled }
            archivedCount = runCatching { conversationsDB.getAllArchived().size }.getOrDefault(0)
            runOnUiThread {
                uiState = uiState.copy(
                    forwardRuleCount = forwardRuleCount,
                    forwardRulesOn = forwardRulesOn,
                    archivedCount = archivedCount,
                )
            }
        }
    }

    private fun loadRecycleBinCount() {
        ensureBackgroundThread {
            recycleBinMessages = messagesDB.getArchivedCount()
            runOnUiThread { uiState = uiState.copy(recycleBinLabel = recycleBinLabel()) }
        }
    }

    // endregion

    private val actions by lazy {
        SettingsActions(
            onBack = ::finish,
            onOpenSpamSettings = { open(SpamSettingsActivity::class.java) },
            onOpenRules = { open(RulesActivity::class.java) },
            onOpenForwardRules = {
                if (ForwardingAvailability.isAvailable(this)) startActivity(ForwardingActivity.intent(this))
            },
            onOpenBlocked = { open(BlockedMessagesActivity::class.java) },
            onOpenDecisionLog = { open(DecisionLogActivity::class.java) },
            onOpenBlockedNumbers = { open(BlockedNumbersActivity::class.java) },
            onOpenBlockedKeywords = { open(ManageBlockedKeywordsActivity::class.java) },
            onRequestSimPermission = { phoneStatePermission.launch(Manifest.permission.READ_PHONE_STATE) },
            onChooseDefaultSim = ::showDefaultSimDialog,
            onCustomizeNotifications = ::launchCustomizeNotificationsIntent,
            onLockScreen = ::showLockScreenDialog,
            onTheme = { startActivity(Intent(this, ThemePresetsActivity::class.java)) },
            onLanguage = ::showLanguageDialog,
            onSpamMode = { mode -> updateSpamSettings { it.copy(mode = mode) } },
            onNotifyOnBlock = { on -> updateSpamSettings { it.copy(notifyOnBlock = on) } },
            onCodeAlert = ::openCodeChannelSettings,
            onCharacterCounter = { config.showCharacterCounter = it; refreshState() },
            onSimpleCharacters = { config.useSimpleCharacters = it; refreshState() },
            onSendOnEnter = { config.sendOnEnter = it; refreshState() },
            onDeliveryReports = { config.enableDeliveryReports = it; refreshState() },
            onLongAsMms = { config.sendLongMessageMMS = it; refreshState() },
            onGroupAsMms = { config.sendGroupMessageMMS = it; refreshState() },
            onMmsLimit = ::showMmsLimitDialog,
            onJalaliDates = { threadPrefs.useJalaliDates = it; refreshState() },
            onBackupSettings = { launchOwnPicker { saveSettingsBackup.launch("uMessage-settings.json") } },
            onRestoreSettings = ::startRestore,
            onSimMessages = { showComposeDialog { dismiss -> SimMessagesDialog(dismiss) } },
            onKeepArchived = { config.keepConversationsArchived = it; refreshState() },
            onRecycleBin = { config.useRecycleBin = it; refreshState() },
            onOpenRecycleBin = { open(RecycleBinConversationsActivity::class.java) },
            onAppLock = ::showSecurityDialog,
            onAppLockTimeout = ::showAppLockTimeoutDialog,
            onAppLockScope = {
                // What the lock covers is changed only by someone who can pass it.
                val confirm = Intent(this, LockActivity::class.java).putExtra(LockActivity.EXTRA_CONFIRM, true)
                confirmLockForScope.launch(confirm)
            },
            onBlockScreenshots = {
                config.appLockBlockScreenshots = it
                AppLockGuard.applyWindowProtection(this)
                refreshState()
            },
            onChangeAppLock = ::changeAppLock,
            onLockNow = {
                appLockManager.lock()
                HiddenChatsSession.lock()
                maybeLaunchAppUnlockActivity(REQUEST_APP_UNLOCK)
            },
            onExport = {
                exportMessagesDialog = ExportMessagesDialog(this) { file ->
                    launchOwnPicker { saveDocument.launch(file) }
                }
            },
            onImport = { launchOwnPicker { getContent.launch(messageImportFileTypes.toTypedArray()) } },
            onOpenRepo = { openLink(REPOSITORY_URL) },
            onOpenLink = ::openLink,
            onShowForwardedTab = { navPrefs.showForwardedTab = it; refreshState() },
            onPauseForwarding = ::setForwardPaused,
            onMaskForwardedCodes = { w3aPrefs.maskForwardedCodes = it; refreshState() },
            onMaskKeepLastDigits = { w3aPrefs.maskKeepLastDigits = it; refreshState() },
            onDailyForwardLimit = ::showDailyLimitDialog,
            onHideCodesOnLockScreen = { w3aPrefs.hideCodesOnLockScreen = it; refreshState() },
            onOpenArchived = { startActivity(MainTabLinks.intent(this, MainTabLinks.TAB_ARCHIVED)) },
            onCopyVersion = ::copyVersion,
            onRate = { if (!FeedbackLinks.rate(this)) toast(R.string.settings_about_no_browser) },
            onSendFeedback = ::sendFeedback,
        )
    }

    /** ID-38: rules, forward rules and settings to a JSON file (no message text). */
    private fun backUpSettings(uri: Uri) {
        lifecycleScope.launch {
            val count = runCatching { SettingsBackup.export(this@SettingsActivity, uri) }.getOrNull()
            val message = if (count != null) {
                resources.getQuantityString(R.plurals.w3c_backup_saved, count, count)
            } else {
                getString(R.string.w3c_backup_failed)
            }
            Toast.makeText(this@SettingsActivity, message, Toast.LENGTH_LONG).show()
        }
    }

    /** Resuming forwarding asks for the app lock like turning a forward rule on does; pausing never asks. */
    private fun setForwardPaused(paused: Boolean) {
        if (ForwardGatePolicy.pauseChangeNeedsCheck(paused, config.isAppPasswordProtectionOn)) {
            val confirm = Intent(this, LockActivity::class.java).putExtra(LockActivity.EXTRA_CONFIRM, true)
            confirmLockForResume.launch(confirm)
        } else {
            navPrefs.forwardPaused = paused
            refreshState()
        }
    }

    /** H1: the app lock first (when it is on), then the file, then a summary to confirm. */
    private fun startRestore() {
        if (config.isAppPasswordProtectionOn) {
            val confirm = Intent(this, LockActivity::class.java).putExtra(LockActivity.EXTRA_CONFIRM, true)
            confirmLockForRestore.launch(confirm)
        } else {
            pickRestoreFile()
        }
    }

    private fun pickRestoreFile() = launchOwnPicker {
        openSettingsBackup.launch(arrayOf("application/json", "text/plain"))
    }

    /**
     * Opens a system file picker the user asked for. Going there and back is not leaving the app, so it does not lock
     * it again (with "lock immediately" a restore asked for the PIN twice: before the picker and after it).
     */
    private inline fun launchOwnPicker(launch: () -> Unit) {
        appLockManager.allowOwnTrip()
        launch()
    }

    /** B3: reads the file without changing anything and shows what a restore would do. */
    private fun previewRestore(uri: Uri) {
        lifecycleScope.launch {
            val (preview, _) = SettingsBackup.read(this@SettingsActivity, uri)
            if (preview == null) {
                Toast.makeText(this@SettingsActivity, getString(R.string.w3c_restore_failed), Toast.LENGTH_LONG).show()
                return@launch
            }
            val summary = RestoreSummary.lines(this@SettingsActivity, preview)
            showComposeDialog { dismiss ->
                ConfirmDialog(
                    title = stringResource(R.string.settings_f_restore_title),
                    message = summary,
                    confirmLabel = stringResource(R.string.settings_f_restore_action),
                    onConfirm = {
                        dismiss()
                        applyRestore(preview)
                    },
                    onDismiss = dismiss,
                )
            }
        }
    }

    private fun applyRestore(preview: SettingsBackup.Preview) {
        lifecycleScope.launch {
            val result = SettingsBackup.apply(this@SettingsActivity, preview)
            if (!result.ok) {
                Toast.makeText(this@SettingsActivity, getString(R.string.w3c_restore_failed), Toast.LENGTH_LONG).show()
                return@launch
            }
            refreshState()
            loadForwardingAndArchived()
            val message = if (result.forwardRulesAdded > 0) {
                getString(R.string.settings_f_restore_done, result.rulesAdded, result.forwardRulesAdded)
            } else {
                getString(R.string.settings_f_restore_done_rules, result.rulesAdded)
            }.plus("\n\n" + getString(R.string.n09_restore_after))
            showComposeDialog { dismiss ->
                InfoDialog(onDismiss = dismiss, title = stringResource(R.string.settings_f_restore_done_title)) {
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    private fun updateSpamSettings(transform: (SpamSettings) -> SpamSettings) {
        val repository = runCatching { SpamRepositoryProvider.get(this) }.getOrNull() ?: return
        lifecycleScope.launch { runCatching { repository.updateSettings(transform) } }
    }

    /** ID-26: Android's own page for the verification-code channel (sound, vibration, pop-up). */
    private fun openCodeChannelSettings() {
        val channel = ensureCodeChannel(this)
        val intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channel)
        if (runCatching { startActivity(intent) }.isFailure) launchCustomizeNotificationsIntent()
    }

    private fun open(target: Class<*>) = startActivity(Intent(this, target))

    /** Puts the full build string (with the commit hash of daily builds) on the clipboard. */
    private fun copyVersion() {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(getString(R.string.settings_about_version), BuildConfig.VERSION_NAME)
        clipboard.setPrimaryClip(clip)
        toast(R.string.settings_nav_version_copied)
    }

    /** Says once that the system stopped the last backup or restore before it finished. */
    private fun reportInterruptedBackup() {
        val kind = BackupJob.takeInterruptedRun(this) ?: return
        val message = getString(
            if (kind == BackupJob.Kind.IMPORT) R.string.backup_interrupted_import else R.string.backup_interrupted_export
        )
        showComposeDialog { dismiss ->
            AlertDialog(
                onDismissRequest = dismiss,
                text = { Text(message) },
                confirmButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.screens_ok)) } },
            )
        }
    }

    /**
     * B16, N02: the phone's language or one of the app's languages, as Android's per-app language: the open screens
     * are recreated in it and the choice shows in the system's app language settings. The first screen shows the
     * same list.
     */
    private fun showLanguageDialog() {
        showComposeDialog { dismiss ->
            LanguageChoiceDialog(
                languages = AppLanguage.choices,
                selected = AppLanguage.current(this@SettingsActivity),
                onSelect = { language ->
                    dismiss()
                    // The commons "use English" switch would fight the per-app language on older Android.
                    config.useEnglish = false
                    AppLanguage.apply(this@SettingsActivity, language)
                    refreshState()
                },
                onDismiss = dismiss,
            )
        }
    }

    private fun showDefaultSimDialog() {
        val items = ArrayList<RadioItem>()
        items.add(RadioItem(DEFAULT_SIM_PHONE, getString(R.string.settings_sim_default_phone)))
        SimCards.load(this).forEach { sim ->
            val carrier = sim.carrier.ifBlank { getString(R.string.settings_sim_slot_unnamed, sim.slot) }
            items.add(RadioItem(sim.subscriptionId, getString(R.string.settings_sim_slot_title, sim.slot, carrier)))
        }
        items.add(RadioItem(DEFAULT_SIM_ASK, getString(R.string.settings_sim_default_ask)))
        showRadioDialog(items, config.defaultSimId, getString(R.string.settings_sim_default_title)) {
            config.defaultSimId = it as Int
            refreshState()
        }
    }

    private fun sendFeedback() {
        val version = BuildConfig.VERSION_NAME.substringBefore('-')
        if (!FeedbackLinks.sendFeedback(this, version)) {
            val message = getString(R.string.settings_feedback_no_app, FeedbackLinks.FEEDBACK_EMAIL)
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun openLink(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        if (runCatching { startActivity(intent) }.isFailure) {
            toast(R.string.settings_about_no_browser)
        }
    }

    // region Dialogs (commons dialogs, same behaviour as before)

    private fun showLockScreenDialog() {
        val items = arrayListOf(
            RadioItem(LOCK_SCREEN_SENDER_MESSAGE, getString(R.string.sender_and_message)),
            RadioItem(LOCK_SCREEN_SENDER, getString(R.string.sender_only)),
            RadioItem(LOCK_SCREEN_NOTHING, getString(org.fossify.commons.R.string.nothing)),
        )
        // B9: the dialog says what is being chosen.
        showRadioDialog(items, config.lockScreenVisibilitySetting, getString(R.string.settings_nav_lock_screen)) {
            config.lockScreenVisibilitySetting = it as Int
            // F10: Android's own lock-screen setting can still show the text, whatever is chosen here.
            if (it != LOCK_SCREEN_SENDER_MESSAGE && LockScreenSystemSetting.systemShowsPrivateContent(this)) {
                toast(R.string.lock_screen_system_shows_text, length = Toast.LENGTH_LONG)
            }
            refreshState()
        }
    }

    private fun lockScreenLabel() = getString(
        when (config.lockScreenVisibilitySetting) {
            LOCK_SCREEN_SENDER_MESSAGE -> R.string.sender_and_message
            LOCK_SCREEN_SENDER -> R.string.sender_only
            else -> org.fossify.commons.R.string.nothing
        }
    )

    private fun dailyLimitLabel(limit: Int) =
        if (limit <= 0) getString(R.string.settings_nav_off) else localizeDigits(limit.toString(), uiLocale)

    private fun showDailyLimitDialog() {
        val items = DAILY_LIMIT_CHOICES.mapIndexed { index, value ->
            RadioItem(index, dailyLimitLabel(value), value)
        }.let { ArrayList(it) }
        val current = w3aPrefs.dailyForwardLimit
        val checkedItemId = items.find { it.value == current }?.id ?: -1
        showRadioDialog(items, checkedItemId, getString(R.string.w3d_daily_limit)) {
            w3aPrefs.dailyForwardLimit = it as Int
            refreshState()
        }
    }

    private fun showMmsLimitDialog() {
        val items = arrayListOf(
            RadioItem(7, sizeLabel(R.string.mms_file_size_limit_none), FILE_SIZE_NONE),
            RadioItem(6, sizeLabel(R.string.mms_file_size_limit_2mb), FILE_SIZE_2_MB),
            RadioItem(5, sizeLabel(R.string.mms_file_size_limit_1mb), FILE_SIZE_1_MB),
            RadioItem(4, sizeLabel(R.string.mms_file_size_limit_600kb), FILE_SIZE_600_KB),
            RadioItem(3, sizeLabel(R.string.mms_file_size_limit_300kb), FILE_SIZE_300_KB),
            RadioItem(2, sizeLabel(R.string.mms_file_size_limit_200kb), FILE_SIZE_200_KB),
            RadioItem(1, sizeLabel(R.string.mms_file_size_limit_100kb), FILE_SIZE_100_KB),
        )
        val checkedItemId = items.find { it.value == config.mmsFileSizeLimit }?.id ?: 7
        showRadioDialog(items, checkedItemId, getString(R.string.settings_nav_mms_size)) {
            config.mmsFileSizeLimit = it as Long
            refreshState()
        }
    }

    /** A size such as "200KB" in the digits of the app's language. */
    private fun sizeLabel(id: Int) = localizeDigits(getString(id), uiLocale)

    private fun mmsFileLimitText() = sizeLabel(
        when (config.mmsFileSizeLimit) {
            FILE_SIZE_100_KB -> R.string.mms_file_size_limit_100kb
            FILE_SIZE_200_KB -> R.string.mms_file_size_limit_200kb
            FILE_SIZE_300_KB -> R.string.mms_file_size_limit_300kb
            FILE_SIZE_600_KB -> R.string.mms_file_size_limit_600kb
            FILE_SIZE_1_MB -> R.string.mms_file_size_limit_1mb
            FILE_SIZE_2_MB -> R.string.mms_file_size_limit_2mb
            else -> R.string.mms_file_size_limit_none
        }
    )

    /** Single-choice dialog over commons [RadioItem]s; [onChoose] gets the chosen item's value. */
    private fun showRadioDialog(
        items: List<RadioItem>,
        checkedId: Int,
        title: String? = null,
        onChoose: (Any) -> Unit,
    ) {
        showComposeDialog { dismiss ->
            ChoiceDialog(
                title = title,
                options = items.map { it.title },
                selectedIndex = items.indexOfFirst { it.id == checkedId },
                onSelect = { index ->
                    dismiss()
                    onChoose(items[index].value)
                },
                onDismiss = dismiss,
            )
        }
    }

    private fun showSecurityDialog() {
        val tabToShow = if (config.isAppPasswordProtectionOn) {
            config.appProtectionType
        } else {
            SHOW_ALL_TABS
        }

        showAppLockDialog(
            requiredHash = config.appPasswordHash,
            showTabIndex = tabToShow
        ) { hash, type, success ->
            if (success) {
                val hasPasswordProtection = config.isAppPasswordProtectionOn
                config.isAppPasswordProtectionOn = !hasPasswordProtection
                config.appPasswordHash = if (hasPasswordProtection) "" else hash
                config.appProtectionType = type
                config.appLockCredentialWeak = false
                AppLockGuard.applyWindowProtection(this)
                refreshState()

                if (config.isAppPasswordProtectionOn) {
                    // Bug 7: the credential was just set and entered; the app does not lock again at once.
                    appLockManager.unlock()
                    val deviceSecure = getSystemService(android.app.KeyguardManager::class.java)?.isDeviceSecure == true
                    val confirmationTextId = lockSetupMessage(config.appProtectionType, deviceSecure)

                    showComposeDialog { dismiss ->
                        InfoDialog(onDismiss = dismiss) {
                            Text(stringResource(confirmationTextId), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }

    private fun timeoutLabel(timeoutMs: Long) = when (timeoutMs) {
        AppLockTimeouts.IMMEDIATELY -> R.string.settings_app_lock_timeout_immediately
        60_000L -> R.string.settings_app_lock_timeout_1m
        5 * 60_000L -> R.string.settings_app_lock_timeout_5m
        else -> R.string.settings_app_lock_timeout_30s
    }

    private fun showAppLockTimeoutDialog() {
        val selected = AppLockTimeouts.selectedIndex(config.unlockTimeoutDurationMs)
        showComposeDialog { dismiss ->
            ChoiceDialog(
                title = getString(R.string.settings_app_lock_timeout),
                options = AppLockTimeouts.choices.map { getString(timeoutLabel(it)) },
                selectedIndex = selected,
                onSelect = { index ->
                    dismiss()
                    config.unlockTimeoutDurationMs = AppLockTimeouts.choices[index]
                    refreshState()
                },
                onDismiss = dismiss,
            )
        }
    }

    /**
     * Replaces the app lock: the current one is asked for first, then a new one is set up from scratch (choosing
     * PIN, pattern or biometrics with a backup PIN). Nothing changes unless both steps finish.
     */
    private fun changeAppLock() {
        if (!config.isAppPasswordProtectionOn) return
        showAppLockDialog(requiredHash = config.appPasswordHash, showTabIndex = config.appProtectionType) { _, _, ok ->
            if (!ok) return@showAppLockDialog
            showAppLockDialog(requiredHash = "", showTabIndex = SHOW_ALL_TABS) { hash, type, success ->
                if (!success) return@showAppLockDialog
                config.appPasswordHash = hash
                config.appProtectionType = type
                config.appLockCredentialWeak = false
                refreshState()
                toast(R.string.settings_app_lock_changed)
            }
        }
    }

    // endregion

    companion object {
        /** String extra: the [SettingsPage] name to open on (e.g. "AppLock"). */
        const val EXTRA_PAGE = "com.glooboo.spamless.extra.SETTINGS_PAGE"

        private val DAILY_LIMIT_CHOICES = listOf(0, 5, 10, 20, 50)
        private const val REPOSITORY_URL = "https://github.com/MyNameIsGlooboo/uMessage"
    }
}
