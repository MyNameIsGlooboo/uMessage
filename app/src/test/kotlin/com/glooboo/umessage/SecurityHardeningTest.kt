package com.glooboo.umessage

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** S7, S12, S16, S17 and I6 (security review): what the manifest, the shrinker rules and .gitignore promise. */
class SecurityHardeningTest {
    private val manifest by lazy {
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
    }

    private fun elements(tag: String): List<Element> {
        val nodes = manifest.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private val androidName = "android:name"

    @Test
    fun theAppIsNotBackedUpAndTheExclusionFilesExist() {
        val app = elements("application").single()
        assertThat(app.getAttribute("android:allowBackup")).isEqualTo("false")
        assertThat(app.getAttribute("android:dataExtractionRules")).isEqualTo("@xml/data_extraction_rules")
        assertThat(app.getAttribute("android:fullBackupContent")).isEqualTo("@xml/backup_rules")
    }

    @Test
    fun noContentProviderIsExported() {
        val providers = elements("provider")
        assertThat(providers).isNotEmpty()
        providers.forEach { assertThat(it.getAttribute("android:exported")).isEqualTo("false") }
    }

    @Test
    fun callPhoneIsDeclaredOnlyForTheTapToCall() {
        val permissions = elements("uses-permission").map { it.getAttribute(androidName) }
        assertThat(permissions).contains("android.permission.CALL_PHONE")
        // No call log access and no outgoing call interception.
        assertThat(permissions).containsNoneOf(
            "android.permission.READ_CALL_LOG", "android.permission.WRITE_CALL_LOG",
            "android.permission.PROCESS_OUTGOING_CALLS",
        )
    }

    @Test
    fun theDeprecatedFingerprintPermissionIsCappedToTheVersionsThatNeedIt() {
        val fingerprint = elements("uses-permission").single {
            it.getAttribute(androidName) == "android.permission.USE_FINGERPRINT"
        }
        assertThat(fingerprint.getAttribute("android:maxSdkVersion")).isEqualTo("27")
    }

    @Test
    fun theBackupServiceIsADataSyncForegroundServiceAndNotExported() {
        val service = elements("service").single { it.getAttribute(androidName) == ".services.BackupService" }
        assertThat(service.getAttribute("android:foregroundServiceType")).isEqualTo("dataSync")
        assertThat(service.getAttribute("android:exported")).isEqualTo("false")
    }

    @Test
    fun releaseBuildsDropLogCallsAndStackTraces() {
        val rules = File("proguard-rules.pro").readText()
        assertThat(rules).contains("-assumenosideeffects class android.util.Log")
        assertThat(rules).contains("-assumenosideeffects class java.lang.Throwable")
        assertThat(rules).contains("printStackTrace")
    }

    @Test
    fun keyFilesAreIgnoredByGit() {
        val ignore = File("../.gitignore").readText().lines().map { it.trim() }
        listOf("*.jks", "*.keystore", "*.p12", "keystore.properties", "play-api*.json").forEach {
            assertThat(ignore).contains(it)
        }
    }
}
