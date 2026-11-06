package com.glooboo.umessage.helpers

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * The deep links of the "Keep uMessage running" guide, one ordered candidate list per step, kept in this one place.
 * [OemHelper.launch] opens the first candidate that resolves and every list ends at the app's own App info page, which
 * on MIUI / HyperOS, ColorOS and One UI also holds the autostart and battery switches.
 *
 * Sources: dontkillmyapp.com (per-vendor pages), the AutoStarter library (judemanutd/AutoStarter, its vendor component
 * table), and AOSP (Settings actions). Every package used here is listed in the manifest's `<queries>` so
 * resolution is reliable on Android 11+; OemIntentsTest checks that.
 *
 * Package names come from the caller ([pkg] is always `context.packageName`), never a constant: the applicationId is
 * com.glooboo.spamless, with a .debug suffix on debug builds.
 */
object OemIntents {

    /** AOSP 12+ per-app battery page (Unrestricted / Optimized / Restricted). An exported Settings deep link. */
    const val ACTION_APP_BATTERY_USAGE = "android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL"

    // Xiaomi, Redmi, POCO: MIUI 12 to 14 and HyperOS 1 to 3 keep both apps.
    const val MIUI_SECURITY = "com.miui.securitycenter"
    const val MIUI_POWERKEEPER = "com.miui.powerkeeper"

    // Samsung Device care: lool everywhere, sm_cn on Chinese firmware, sm on One UI 1 and older.
    const val SAMSUNG_LOOL = "com.samsung.android.lool"
    const val SAMSUNG_SM_CN = "com.samsung.android.sm_cn"
    const val SAMSUNG_SM = "com.samsung.android.sm"

    const val HUAWEI_MANAGER = "com.huawei.systemmanager"
    const val HONOR_MANAGER = "com.hihonor.systemmanager"

    const val COLOROS_SAFECENTER = "com.coloros.safecenter"
    const val OPPO_SAFE = "com.oppo.safe"
    const val ONEPLUS_SECURITY = "com.oneplus.security"

    const val VIVO_PERMISSIONS = "com.vivo.permissionmanager"
    const val IQOO_SECURE = "com.iqoo.secure"

    /** Every OEM package a candidate may point at: must match the manifest's `<queries>`. */
    val packages: Set<String> = setOf(
        MIUI_SECURITY, MIUI_POWERKEEPER,
        SAMSUNG_LOOL, SAMSUNG_SM_CN, SAMSUNG_SM,
        HUAWEI_MANAGER, HONOR_MANAGER,
        COLOROS_SAFECENTER, OPPO_SAFE, ONEPLUS_SECURITY,
        VIVO_PERMISSIONS, IQOO_SECURE,
    )

    /** Xiaomi Security > Autostart. Same class on MIUI 12 to 14 and HyperOS; App info has an Autostart switch too. */
    fun xiaomiAutostart(pkg: String): List<Intent> = listOf(
        component(MIUI_SECURITY, "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        appDetails(pkg),
    )

    /**
     * Xiaomi per-app Battery saver (No restrictions / Battery saver / Restrict). HiddenAppsConfigActivity opens the
     * app's own page when given package_name and package_label, the container activity opens the list of apps.
     */
    fun xiaomiBatterySaver(pkg: String, label: String): List<Intent> = listOf(
        component(MIUI_POWERKEEPER, "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
            .putExtra("package_name", pkg)
            .putExtra("package_label", label),
        component(MIUI_POWERKEEPER, "com.miui.powerkeeper.ui.HiddenAppsContainerManagementActivity"),
        appDetails(pkg),
    )

    /** Samsung Device care > Battery, whose Background usage limits holds the Sleeping and Deep sleeping lists. */
    fun samsungSleepingApps(pkg: String): List<Intent> = listOf(
        component(SAMSUNG_LOOL, "com.samsung.android.sm.battery.ui.BatteryActivity"),
        component(SAMSUNG_LOOL, "com.samsung.android.sm.ui.battery.BatteryActivity"),
        component(SAMSUNG_SM_CN, "com.samsung.android.sm.battery.ui.BatteryActivity"),
        component(SAMSUNG_SM_CN, "com.samsung.android.sm.ui.battery.BatteryActivity"),
        component(SAMSUNG_SM, "com.samsung.android.sm.ui.battery.BatteryActivity"),
        appDetails(pkg),
    )

    /** Huawei EMUI 9+ / Honor MagicOS App launch, then EMUI 8 and the older Protected apps list. */
    fun huaweiAppLaunch(pkg: String): List<Intent> = listOf(
        component(HUAWEI_MANAGER, "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        component(HONOR_MANAGER, "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        component(HUAWEI_MANAGER, "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
        component(HUAWEI_MANAGER, "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        appDetails(pkg),
    )

    /**
     * Oppo, Realme, OnePlus startup managers of ColorOS 2 to 11 and OxygenOS up to 11. ColorOS / OxygenOS 12+ moved
     * Allow auto launch and Allow background activity to App info > Battery usage, the last candidate.
     */
    fun oppoStartup(pkg: String): List<Intent> = listOf(
        component(COLOROS_SAFECENTER, "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        component(COLOROS_SAFECENTER, "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        component(OPPO_SAFE, "com.oppo.safe.permission.startup.StartupAppListActivity"),
        component(ONEPLUS_SECURITY, "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        appDetails(pkg),
    )

    /** Vivo / iQOO background startup manager (Funtouch OS, OriginOS), then the iManager whitelist. */
    fun vivoBackground(pkg: String): List<Intent> = listOf(
        component(VIVO_PERMISSIONS, "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        component(IQOO_SECURE, "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        component(IQOO_SECURE, "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
        appDetails(pkg),
    )

    /**
     * Android battery optimization, on every phone. Not ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS: that dialog
     * silently closes unless the app holds REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, which uMessage does not declare
     * (Play allows it only for apps that cannot get messages any other way, and SMS arrive by broadcast even in Doze).
     * The app's own battery page (Android 12+) comes first, then the system list.
     */
    fun ignoreBatteryOptimization(pkg: String): List<Intent> = listOf(
        Intent(ACTION_APP_BATTERY_USAGE).setData(packageUri(pkg)),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        appDetails(pkg),
    )

    fun appDetails(pkg: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(packageUri(pkg))

    private fun packageUri(pkg: String): Uri = Uri.fromParts("package", pkg, null)

    private fun component(pkg: String, cls: String) = Intent().setComponent(ComponentName(pkg, cls))
}
