package com.glooboo.umessage.helpers

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.annotation.StringRes
import com.glooboo.umessage.R

enum class Oem(@StringRes val labelRes: Int) {
    XIAOMI(R.string.onb_oem_xiaomi),
    SAMSUNG(R.string.onb_oem_samsung),
    HUAWEI(R.string.onb_oem_huawei),
    OPPO_FAMILY(R.string.onb_oem_oppo),
    VIVO(R.string.oem2_vivo),
    OTHER(R.string.onb_oem_oppo),
}

/** How a step's done state is known. */
enum class StepCheck {
    /** Read from a public API every time the screen resumes. */
    VERIFIED,

    /** No API can read it: done once the user opened the screen from the guide and came back, until they undo it. */
    USER_CONFIRMED,
}

/** The guide's steps. [key] is persisted, so it never changes. */
enum class OemStepKind(val key: String, val check: StepCheck, val backgroundLimit: Boolean) {
    XIAOMI_AUTOSTART("xiaomi_autostart", StepCheck.USER_CONFIRMED, backgroundLimit = false),
    XIAOMI_BATTERY_SAVER("xiaomi_battery_saver", StepCheck.USER_CONFIRMED, backgroundLimit = true),
    SAMSUNG_SLEEPING("samsung_sleeping", StepCheck.USER_CONFIRMED, backgroundLimit = true),
    HUAWEI_LAUNCH("huawei_launch", StepCheck.USER_CONFIRMED, backgroundLimit = false),
    OPPO_STARTUP("oppo_startup", StepCheck.USER_CONFIRMED, backgroundLimit = true),
    VIVO_BACKGROUND("vivo_background", StepCheck.USER_CONFIRMED, backgroundLimit = true),
    IGNORE_BATTERY("ignore_battery", StepCheck.VERIFIED, backgroundLimit = true),
    APP_INFO("app_info", StepCheck.USER_CONFIRMED, backgroundLimit = false),
}

/** One manual step the user must do on their phone. [candidates] are tried in order. */
data class OemStep(
    val kind: OemStepKind,
    @StringRes val titleRes: Int,
    @StringRes val descRes: Int,
    val candidates: List<Intent>,
)

/** What Android itself reports about uMessage's background limits. */
data class BackgroundStatus(
    /** PowerManager.isIgnoringBatteryOptimizations: Unrestricted / Don't optimize. */
    val ignoringBatteryOptimizations: Boolean,
    /** ActivityManager.isBackgroundRestricted: the user chose Restricted, or an OEM list applied it. */
    val backgroundRestricted: Boolean,
    /** UsageStatsManager standby bucket is RESTRICTED (Android 11+), as for Samsung's deep sleeping apps. */
    val restrictedBucket: Boolean,
)

enum class StepState { NOT_DONE, DONE_VERIFIED, DONE_BY_USER }

/**
 * Detects aggressive-battery OEMs, builds the guide's steps from [OemIntents], opens them and works out which are done.
 */
object OemHelper {

    /** UsageStatsManager.STANDBY_BUCKET_RESTRICTED, a constant only from API 30. */
    private const val STANDBY_BUCKET_RESTRICTED = 45

    fun detect(
        manufacturer: String = Build.MANUFACTURER,
        brand: String = Build.BRAND,
    ): Oem {
        val value = "${manufacturer.lowercase()} ${brand.lowercase()}"
        return when {
            listOf("xiaomi", "redmi", "poco").any { value.contains(it) } -> Oem.XIAOMI
            value.contains("samsung") -> Oem.SAMSUNG
            listOf("huawei", "honor").any { value.contains(it) } -> Oem.HUAWEI
            listOf("vivo", "iqoo").any { value.contains(it) } -> Oem.VIVO
            listOf("oppo", "realme", "oneplus").any { value.contains(it) } -> Oem.OPPO_FAMILY
            else -> Oem.OTHER
        }
    }

    /** OEM-specific steps first, then the generic ones that work everywhere. */
    fun steps(context: Context, oem: Oem = detect()): List<OemStep> {
        val label = context.applicationInfo.loadLabel(context.packageManager).toString()
        return steps(context.packageName, label, oem)
    }

    fun steps(pkg: String, label: String, oem: Oem): List<OemStep> {
        val oemSteps = when (oem) {
            Oem.XIAOMI -> listOf(
                OemStep(
                    OemStepKind.XIAOMI_AUTOSTART,
                    R.string.onb_oem_step_autostart,
                    R.string.oem2_xiaomi_autostart_desc,
                    OemIntents.xiaomiAutostart(pkg),
                ),
                OemStep(
                    OemStepKind.XIAOMI_BATTERY_SAVER,
                    R.string.onb_oem_step_battery_xiaomi,
                    R.string.oem2_xiaomi_battery_desc,
                    OemIntents.xiaomiBatterySaver(pkg, label),
                ),
            )

            Oem.SAMSUNG -> listOf(
                OemStep(
                    OemStepKind.SAMSUNG_SLEEPING,
                    R.string.onb_oem_step_samsung_sleep,
                    R.string.onb_oem_step_samsung_sleep_desc,
                    OemIntents.samsungSleepingApps(pkg),
                ),
            )

            Oem.HUAWEI -> listOf(
                OemStep(
                    OemStepKind.HUAWEI_LAUNCH,
                    R.string.onb_oem_step_huawei_launch,
                    R.string.onb_oem_step_huawei_launch_desc,
                    OemIntents.huaweiAppLaunch(pkg),
                ),
            )

            Oem.OPPO_FAMILY -> listOf(
                OemStep(
                    OemStepKind.OPPO_STARTUP,
                    R.string.onb_oem_step_oppo_startup,
                    R.string.oem2_oppo_desc,
                    OemIntents.oppoStartup(pkg),
                ),
            )

            Oem.VIVO -> listOf(
                OemStep(
                    OemStepKind.VIVO_BACKGROUND,
                    R.string.oem2_step_vivo,
                    R.string.oem2_step_vivo_desc,
                    OemIntents.vivoBackground(pkg),
                ),
            )

            Oem.OTHER -> emptyList()
        }

        val generic = listOf(
            OemStep(
                OemStepKind.IGNORE_BATTERY,
                R.string.onb_oem_step_ignore_battery,
                R.string.oem2_ignore_battery_desc,
                OemIntents.ignoreBatteryOptimization(pkg),
            ),
            OemStep(
                OemStepKind.APP_INFO,
                R.string.onb_oem_step_app_info,
                R.string.onb_oem_step_app_info_desc,
                listOf(OemIntents.appDetails(pkg)),
            ),
        )
        return oemSteps + generic
    }

    /**
     * A step's state. A verified step is done only when Android says so. A user-confirmed step is done when the user
     * marked it, except a background-limit step while Android reports a background limit: that is verifiably not done.
     */
    fun stateOf(kind: OemStepKind, status: BackgroundStatus, markedByUser: Boolean): StepState {
        val limited = status.backgroundRestricted || status.restrictedBucket
        return when {
            kind.check == StepCheck.VERIFIED ->
                if (status.ignoringBatteryOptimizations && !limited) StepState.DONE_VERIFIED else StepState.NOT_DONE

            kind.backgroundLimit && limited -> StepState.NOT_DONE
            markedByUser -> StepState.DONE_BY_USER
            else -> StepState.NOT_DONE
        }
    }

    fun backgroundStatus(context: Context): BackgroundStatus {
        val restricted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isBackgroundRestricted == true
        } else {
            false
        }
        val bucket = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            (context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager)?.appStandbyBucket
        } else {
            null
        }
        return BackgroundStatus(
            ignoringBatteryOptimizations = isIgnoringBatteryOptimizations(context),
            backgroundRestricted = restricted,
            restrictedBucket = bucket != null && bucket >= STANDBY_BUCKET_RESTRICTED,
        )
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Opens the first candidate that resolves to an exported activity; if starting it throws, tries the next. The app
     * details page is the last resort even when it did not resolve.
     * @return the intent that was started, or null when nothing could be opened.
     */
    fun launch(
        context: Context,
        step: OemStep,
        start: (Intent) -> Unit = { context.startActivity(it) },
    ): Intent? {
        val details = OemIntents.appDetails(context.packageName)
        val resolving = step.candidates.filter { resolves(context.packageManager, it) }
        for (candidate in (resolving + details).distinctBy { it.toUri(0) }) {
            val intent = Intent(candidate).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                start(intent)
                return candidate
            } catch (_: ActivityNotFoundException) {
                // Gone on this firmware: try the next candidate.
            } catch (_: SecurityException) {
                // Not exported or blocked by the OEM: try the next candidate.
            }
        }
        return null
    }

    /** Package visibility is covered by the manifest's `<queries>`, so a candidate that does not resolve is absent. */
    fun resolves(pm: PackageManager, intent: Intent): Boolean {
        val info = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo ?: return false
        return info.exported
    }
}
