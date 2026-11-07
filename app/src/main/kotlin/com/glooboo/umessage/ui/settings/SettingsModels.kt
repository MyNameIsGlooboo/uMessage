package com.glooboo.umessage.ui.settings

import com.glooboo.umessage.ui.lock.AppLockAdvice
import com.glooboo.umessage.helpers.DEFAULT_SIM_PHONE
import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import androidx.compose.runtime.Immutable
import androidx.core.content.ContextCompat
import com.glooboo.umessage.spam.UnknownSenderMode

/** Spam protection headline for the top card. */
@Immutable
data class SpamSummary(
    val mode: UnknownSenderMode,
    val blockedLast7Days: Int,
    val blockedTotal: Int,
    val restoredTotal: Int,
    /** SE-09: a quiet notification for each blocked message, switched from the Notifications group. */
    val notifyOnBlock: Boolean = false,
)

/** An active SIM. [slot] is 1-based. [number] is null when the platform does not expose it. */
@Immutable
data class SimCard(
    val subscriptionId: Int,
    val slot: Int,
    val carrier: String,
    val number: String?,
    val isDefaultSms: Boolean,
)

/** Everything the settings screen shows, read from Config and the system. Plain values only. */
@Immutable
@Suppress("LongParameterList")
data class SettingsUiState(
    val spam: SpamSummary? = null,
    /** SU-33: the old blocked-words list still has words; once they are moved into rules its row is hidden. */
    val showOldBlockedWords: Boolean = false,
    val simPermissionGranted: Boolean = true,
    val sims: List<SimCard> = emptyList(),
    val defaultSimId: Int = DEFAULT_SIM_PHONE,

    val lockScreenLabel: String = "",
    /** B16 / ID-35: the in-app language row (System / English / Persian) and its current value. */
    val showLanguage: Boolean = false,
    val languageName: String = "",

    val showCharacterCounter: Boolean = false,
    val useSimpleCharacters: Boolean = false,
    val sendOnEnter: Boolean = false,
    val deliveryReports: Boolean = false,
    val sendLongAsMms: Boolean = false,
    val sendGroupAsMms: Boolean = false,
    val mmsLimitLabel: String = "",
    val keepArchived: Boolean = false,
    /** TH-03: dates in the thread use the Jalali calendar. */
    val useJalaliDates: Boolean = false,
    val useRecycleBin: Boolean = false,
    val recycleBinLabel: String = "",
    val appLock: Boolean = false,
    /** Shown only while the lock is on. */
    val appLockTimeoutLabel: String = "",
    /** I44: what the lock covers, shown only while the lock is on. */
    val appLockScopeLabel: String = "",
    val appLockBlockScreenshots: Boolean = false,
    val appLockAdvice: AppLockAdvice = AppLockAdvice.None,

    val showDecisionLog: Boolean = false,
    val versionName: String = "",
    /** Version with the build suffix (commit hash), copied by a tap on the Version row. */
    val fullVersionName: String = "",
    /** True only in the Google Play build: the "Rate uMessage" row. */
    val showRateRow: Boolean = false,

    /** Forward rules saved, and how many of them are switched on: the Forwarding row's summary (round 3). */
    /** False in the Google Play build: no Forwarding row, page or search result. */
    val forwardingAvailable: Boolean = true,
    val forwardRuleCount: Int = 0,
    val forwardRulesOn: Int = 0,
    val showForwardedTab: Boolean = false,
    val pauseAllForwarding: Boolean = false,
    /** Wave 3: codes in forwarded messages show as ****, optionally keeping the last two digits. */
    val maskForwardedCodes: Boolean = false,
    val maskKeepLastDigits: Boolean = false,
    /** Daily forward limit, 0 = off; [dailyForwardLimitLabel] is "Off" or the number. */
    val dailyForwardLimit: Int = 0,
    val dailyForwardLimitLabel: String = "",
    val hideCodesOnLockScreen: Boolean = false,
    /** Archived conversations; null hides the row (needs the host to provide a route). */
    val archivedCount: Int? = null,
) {
    /** P4-05: every SIM control is hidden unless two SIMs are active (an unknown count shows nothing). */
    val isDualSim: Boolean get() = sims.size > 1

    /**
     * Round 3: the Forwarding row is always on the main list (the owner could not find forwarding without a rule), so
     * its summary says what forwarding does right now.
     */
    val forwardingSummary: ForwardingSummary get() = when {
        forwardRuleCount == 0 -> ForwardingSummary.NO_RULES
        pauseAllForwarding -> ForwardingSummary.PAUSED
        forwardRulesOn == 0 -> ForwardingSummary.OFF
        else -> ForwardingSummary.ON
    }
}

/** What the Forwarding row of the main list says: no rules yet, every rule off, all paused, or some rules on. */
enum class ForwardingSummary { NO_RULES, OFF, PAUSED, ON }

/** The pages of Settings: the main list and the screens it opens. */
enum class SettingsPage { Main, Spam, Forwarding, Notifications, Typing, Storage, Backup, AppLock, About, Licenses }

/** Everything the screen can ask the host to do. */
@Immutable
@Suppress("LongParameterList")
class SettingsActions(
    val onBack: () -> Unit = {},
    val onOpenSpamSettings: () -> Unit = {},
    val onOpenRules: () -> Unit = {},
    /** The Forwarding screen (forward rules and their editor), from the main list's Forwarding row. */
    val onOpenForwardRules: () -> Unit = {},
    val onOpenBlocked: () -> Unit = {},
    val onOpenDecisionLog: () -> Unit = {},
    val onOpenBlockedNumbers: () -> Unit = {},
    val onOpenBlockedKeywords: () -> Unit = {},
    val onRequestSimPermission: () -> Unit = {},
    val onChooseDefaultSim: () -> Unit = {},
    val onCustomizeNotifications: () -> Unit = {},
    val onLockScreen: () -> Unit = {},
    val onTheme: () -> Unit = {},
    val onLanguage: () -> Unit = {},
    /** P4-11: the unknown-sender mode, chosen on the Spam protection page (Strict is confirmed there first). */
    val onSpamMode: (UnknownSenderMode) -> Unit = {},
    /** SE-09: "Notify when a message is blocked". */
    val onNotifyOnBlock: (Boolean) -> Unit = {},
    /** ID-26: opens Android's settings of the verification-code notification channel. */
    val onCodeAlert: () -> Unit = {},
    val onCharacterCounter: (Boolean) -> Unit = {},
    val onSimpleCharacters: (Boolean) -> Unit = {},
    val onSendOnEnter: (Boolean) -> Unit = {},
    val onDeliveryReports: (Boolean) -> Unit = {},
    val onLongAsMms: (Boolean) -> Unit = {},
    val onGroupAsMms: (Boolean) -> Unit = {},
    val onMmsLimit: () -> Unit = {},
    val onKeepArchived: (Boolean) -> Unit = {},
    val onJalaliDates: (Boolean) -> Unit = {},
    val onBackupSettings: () -> Unit = {},
    val onRestoreSettings: () -> Unit = {},
    val onSimMessages: () -> Unit = {},
    val onRecycleBin: (Boolean) -> Unit = {},
    val onOpenRecycleBin: () -> Unit = {},
    val onAppLock: () -> Unit = {},
    val onAppLockTimeout: () -> Unit = {},
    val onAppLockScope: () -> Unit = {},
    val onBlockScreenshots: (Boolean) -> Unit = {},
    val onChangeAppLock: () -> Unit = {},
    val onLockNow: () -> Unit = {},
    val onExport: () -> Unit = {},
    val onImport: () -> Unit = {},
    val onOpenRepo: () -> Unit = {},
    /** Opens a web page (licence texts) in the browser. */
    val onOpenLink: (String) -> Unit = {},
    val onShowForwardedTab: (Boolean) -> Unit = {},
    val onPauseForwarding: (Boolean) -> Unit = {},
    val onMaskForwardedCodes: (Boolean) -> Unit = {},
    val onMaskKeepLastDigits: (Boolean) -> Unit = {},
    val onDailyForwardLimit: () -> Unit = {},
    val onHideCodesOnLockScreen: (Boolean) -> Unit = {},
    /** Null hides the "Archived conversations" row. */
    val onOpenArchived: (() -> Unit)? = null,
    val onCopyVersion: () -> Unit = {},
    val onRate: () -> Unit = {},
    val onSendFeedback: () -> Unit = {},
)

/** Reads active SIMs. Empty when READ_PHONE_STATE is not granted or the platform refuses. */
object SimCards {
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission", "HardwareIds")
    @Suppress("DEPRECATION")
    fun load(context: Context): List<SimCard> {
        if (!hasPermission(context)) return emptyList()
        return try {
            val manager = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
            val defaultId = SubscriptionManager.getDefaultSmsSubscriptionId()
            manager.activeSubscriptionInfoList.orEmpty().map { info ->
                val number = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        manager.getPhoneNumber(info.subscriptionId)
                    } else {
                        info.number
                    }
                }.getOrNull()?.takeIf { it.isNotBlank() }
                SimCard(
                    subscriptionId = info.subscriptionId,
                    slot = info.simSlotIndex + 1,
                    carrier = info.carrierName?.toString().orEmpty(),
                    number = number,
                    isDefaultSms = info.subscriptionId == defaultId,
                )
            }.sortedBy { it.slot }
        } catch (_: SecurityException) {
            emptyList()
        }
    }
}
