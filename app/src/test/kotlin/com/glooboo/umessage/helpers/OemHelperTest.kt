package com.glooboo.umessage.helpers

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The "Keep uMessage running" candidate tables, the launch fallback chain and the done-tick rules. */
@RunWith(AndroidJUnit4::class)
class OemHelperTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val pkg = "com.example.under.test"

    private fun addExported(component: ComponentName) {
        val pm = shadowOf(context.packageManager)
        pm.addActivityIfNotPresent(component)
        pm.addOrUpdateActivity(context.packageManager.getActivityInfo(component, 0).apply { exported = true })
    }

    @Test
    fun hiddenActivitiesAreSkipped() {
        val component = OemIntents.xiaomiAutostart(pkg).first().component!!
        shadowOf(context.packageManager).addActivityIfNotPresent(component)
        assertThat(OemHelper.resolves(context.packageManager, Intent().setComponent(component))).isFalse()
        addExported(component)
        assertThat(OemHelper.resolves(context.packageManager, Intent().setComponent(component))).isTrue()
    }

    private fun Intent.target(): String = component?.flattenToString() ?: action.orEmpty()

    @Test
    fun detectsEveryVendor() {
        assertThat(OemHelper.detect("Xiaomi", "Redmi")).isEqualTo(Oem.XIAOMI)
        assertThat(OemHelper.detect("Xiaomi", "POCO")).isEqualTo(Oem.XIAOMI)
        assertThat(OemHelper.detect("samsung", "samsung")).isEqualTo(Oem.SAMSUNG)
        assertThat(OemHelper.detect("HONOR", "HONOR")).isEqualTo(Oem.HUAWEI)
        assertThat(OemHelper.detect("vivo", "iQOO")).isEqualTo(Oem.VIVO)
        assertThat(OemHelper.detect("OnePlus", "OnePlus")).isEqualTo(Oem.OPPO_FAMILY)
        assertThat(OemHelper.detect("realme", "realme")).isEqualTo(Oem.OPPO_FAMILY)
        assertThat(OemHelper.detect("Google", "google")).isEqualTo(Oem.OTHER)
    }

    @Test
    fun everyStepEndsAtThisAppsDetailsPage() {
        Oem.entries.forEach { oem ->
            val steps = OemHelper.steps(pkg, "uMessage", oem)
            assertThat(steps.map { it.kind }).containsAtLeast(OemStepKind.IGNORE_BATTERY, OemStepKind.APP_INFO)
            steps.forEach { step ->
                val last = step.candidates.last()
                assertThat(last.action).isEqualTo(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                assertThat(last.data.toString()).isEqualTo("package:$pkg")
                assertThat(step.candidates.map { it.toUri(0) }).containsNoDuplicates()
            }
        }
    }

    @Test
    fun keyStepsArePersistedUnderStableUniqueKeys() {
        assertThat(OemStepKind.entries.map { it.key }).containsNoDuplicates()
        assertThat(OemStepKind.XIAOMI_AUTOSTART.key).isEqualTo("xiaomi_autostart")
    }

    @Test
    fun neverUsesTheBatteryDialogThatNeedsAnUndeclaredPermission() {
        Oem.entries.flatMap { OemHelper.steps(pkg, "uMessage", it) }.flatMap { it.candidates }.forEach {
            assertThat(it.action).isNotEqualTo(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        }
    }

    @Test
    fun xiaomiBatterySaverOpensThisAppsOwnPage() {
        val first = OemIntents.xiaomiBatterySaver(pkg, "uMessage").first()
        assertThat(first.component?.flattenToString())
            .isEqualTo("com.miui.powerkeeper/com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
        assertThat(first.getStringExtra("package_name")).isEqualTo(pkg)
        assertThat(first.getStringExtra("package_label")).isEqualTo("uMessage")
    }

    @Test
    fun stepsUseTheRealPackageName() {
        val steps = OemHelper.steps(context, Oem.XIAOMI)
        assertThat(steps.last().candidates.single().data.toString()).isEqualTo("package:${context.packageName}")
        assertThat(steps[1].candidates.first().getStringExtra("package_name")).isEqualTo(context.packageName)
    }

    @Test
    fun everyOemPackageIsInTheManifestQueries() {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val queries = doc.getElementsByTagName("queries").item(0) as org.w3c.dom.Element
        val nodes = queries.getElementsByTagName("package")
        val declared = (0 until nodes.length).map {
            (nodes.item(it) as org.w3c.dom.Element).getAttribute("android:name")
        }.toSet()
        val used = Oem.entries.flatMap { OemHelper.steps(pkg, "uMessage", it) }
            .flatMap { it.candidates }.mapNotNull { it.component?.packageName }.toSet()
        assertThat(OemIntents.packages).isEqualTo(used)
        assertThat(declared).isEqualTo(used)
    }

    @Test
    fun launchSkipsCandidatesThatDoNotResolve() {
        val step = OemHelper.steps(context, Oem.XIAOMI).first { it.kind == OemStepKind.XIAOMI_BATTERY_SAVER }
        val list = ComponentName(
            OemIntents.MIUI_POWERKEEPER,
            "com.miui.powerkeeper.ui.HiddenAppsContainerManagementActivity",
        )
        addExported(list)
        val started = mutableListOf<Intent>()

        val opened = OemHelper.launch(context, step) { started += it }

        assertThat(opened?.component).isEqualTo(list)
        assertThat(started.map { it.target() }).containsExactly(list.flattenToString())
        assertThat(started.single().flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
    }

    @Test
    fun launchTriesTheNextCandidateWhenStartingThrowsAndEndsAtAppDetails() {
        val step = OemHelper.steps(context, Oem.XIAOMI).first { it.kind == OemStepKind.XIAOMI_AUTOSTART }
        val autostart = step.candidates.first().component!!
        addExported(autostart)
        val tried = mutableListOf<String>()

        val opened = OemHelper.launch(context, step) {
            tried += it.target()
            if (it.component == autostart) throw SecurityException("not exported")
        }

        assertThat(tried).containsExactly(autostart.flattenToString(), Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .inOrder()
        assertThat(opened?.action).isEqualTo(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    }

    @Test
    fun launchPrefersTheAppBatteryPageWhenTheSystemHasIt() {
        val step = OemHelper.steps(context, Oem.OTHER).first { it.kind == OemStepKind.IGNORE_BATTERY }
        val settings = ComponentName("com.android.settings", "com.android.settings.AppBatteryUsage")
        addExported(settings)
        shadowOf(context.packageManager).addIntentFilterForActivity(
            settings,
            IntentFilter(OemIntents.ACTION_APP_BATTERY_USAGE).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme("package")
            },
        )
        val started = mutableListOf<Intent>()

        OemHelper.launch(context, step) { started += it }

        assertThat(started.single().action).isEqualTo(OemIntents.ACTION_APP_BATTERY_USAGE)
        assertThat(started.single().data.toString()).isEqualTo("package:${context.packageName}")
    }

    @Test
    fun launchReturnsNullWhenNothingOpens() {
        val step = OemHelper.steps(context, Oem.OTHER).first { it.kind == OemStepKind.APP_INFO }
        assertThat(OemHelper.launch(context, step) { throw ActivityNotFoundException() }).isNull()
    }

    private val free = BackgroundStatus(
        ignoringBatteryOptimizations = true,
        backgroundRestricted = false,
        restrictedBucket = false,
    )
    private val optimized = free.copy(ignoringBatteryOptimizations = false)

    @Test
    fun verifiedStepFollowsAndroidOnly() {
        val kind = OemStepKind.IGNORE_BATTERY
        assertThat(OemHelper.stateOf(kind, free, markedByUser = false)).isEqualTo(StepState.DONE_VERIFIED)
        assertThat(OemHelper.stateOf(kind, optimized, markedByUser = true)).isEqualTo(StepState.NOT_DONE)
        assertThat(OemHelper.stateOf(kind, free.copy(backgroundRestricted = true), false)).isEqualTo(StepState.NOT_DONE)
        assertThat(OemHelper.stateOf(kind, free.copy(restrictedBucket = true), false)).isEqualTo(StepState.NOT_DONE)
    }

    @Test
    fun userConfirmedStepIsDoneOnlyWhenMarked() {
        val kind = OemStepKind.XIAOMI_AUTOSTART
        assertThat(OemHelper.stateOf(kind, optimized, markedByUser = false)).isEqualTo(StepState.NOT_DONE)
        assertThat(OemHelper.stateOf(kind, optimized, markedByUser = true)).isEqualTo(StepState.DONE_BY_USER)
        // Autostart is not a background limit, so a restricted bucket says nothing about it.
        assertThat(OemHelper.stateOf(kind, optimized.copy(restrictedBucket = true), true))
            .isEqualTo(StepState.DONE_BY_USER)
    }

    @Test
    fun markedBackgroundStepIsNotDoneWhileAndroidStillLimitsTheApp() {
        val kind = OemStepKind.SAMSUNG_SLEEPING
        assertThat(OemHelper.stateOf(kind, optimized, markedByUser = true)).isEqualTo(StepState.DONE_BY_USER)
        assertThat(OemHelper.stateOf(kind, optimized.copy(restrictedBucket = true), true)).isEqualTo(StepState.NOT_DONE)
        assertThat(OemHelper.stateOf(kind, optimized.copy(backgroundRestricted = true), true))
            .isEqualTo(StepState.NOT_DONE)
    }

    @Test
    fun backgroundStatusReadsThePowerAllowlist() {
        val power = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        shadowOf(power).setIgnoringBatteryOptimizations(context.packageName, true)
        assertThat(OemHelper.backgroundStatus(context).ignoringBatteryOptimizations).isTrue()
        shadowOf(power).setIgnoringBatteryOptimizations(context.packageName, false)
        assertThat(OemHelper.backgroundStatus(context).ignoringBatteryOptimizations).isFalse()
    }
}
