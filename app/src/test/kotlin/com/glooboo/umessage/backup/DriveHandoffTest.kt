package com.glooboo.umessage.backup

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.CacheCleaner
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.IOException
import javax.xml.parsers.DocumentBuilderFactory

/**
 * "Save to Google Drive" without network code: the backup is one cache file, handed to the Drive app with a
 * package-restricted ACTION_SEND, and the app never gets an INTERNET permission.
 */
@RunWith(AndroidJUnit4::class)
class DriveHandoffTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val app: Application get() = context as Application

    /** FileProvider cannot resolve cache folders under Robolectric; the real one is checked on the emulator. */
    @Before
    fun fakeProviderUris() {
        DriveHandoff.uriFactory = { c, f ->
            android.net.Uri.parse("content://${c.packageName}.provider/drive_backups/${f.name}")
        }
    }

    @After
    fun restoreProviderUris() {
        DriveHandoff.uriFactory = DriveHandoff.defaultUriFactory
    }

    private fun backupFile(content: String = "{\"messages\":[]}"): File =
        DriveHandoff.newFile(context, "backup.json").also { it.writeText(content) }

    private fun installDrive() {
        val probe = Intent(Intent.ACTION_SEND).setPackage(DriveHandoff.PACKAGE).setType("application/octet-stream")
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = DriveHandoff.PACKAGE
                name = "com.google.android.apps.docs.UploadMenuActivity"
            }
        }
        shadowOf(context.packageManager).addResolveInfoForIntent(probe, info)
    }

    @Test
    fun theIntentGoesToTheDriveAppOnlyWithTheFileAndAReadGrant() {
        val file = backupFile()

        val intent = DriveHandoff.sendIntent(context, file, "application/json")

        assertThat(intent.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(intent.`package`).isEqualTo("com.google.android.apps.docs")
        assertThat(intent.type).isEqualTo("application/json")
        assertThat(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
        assertThat(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION).isEqualTo(0)
        val stream = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        assertThat(stream?.scheme).isEqualTo("content")
        assertThat(stream?.authority).isEqualTo("${context.packageName}.provider")
        assertThat(stream?.lastPathSegment).isEqualTo("backup.json")
        assertThat(intent.clipData?.getItemAt(0)?.uri).isEqualTo(stream)
    }

    @Test
    fun theFileSitsInAFolderTheCleanerEmptiesAndTheProviderServes() {
        val file = backupFile()
        assertThat(file.parentFile?.name).isEqualTo(DriveHandoff.DIR)
        assertThat(CacheCleaner.FOLDERS).contains(DriveHandoff.DIR)
        // the folder is one of the FileProvider roots, under the authority the manifest declares
        val paths = File("src/main/res/xml/provider_paths.xml").readText()
        assertThat(paths).contains("<cache-path name=\"drive_backups\" path=\"${DriveHandoff.DIR}/\"/>")
        assertThat(File("src/main/AndroidManifest.xml").readText()).contains("{applicationId}.provider")

        file.setLastModified(System.currentTimeMillis() - CacheCleaner.MAX_AGE_MS - 1000)
        CacheCleaner.clean(context.cacheDir, System.currentTimeMillis())
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun onlyOneHandOverFileIsKept() {
        val first = backupFile()
        val second = DriveHandoff.newFile(context, "second.json").also { it.writeText("x") }

        assertThat(first.exists()).isFalse()
        assertThat(second.exists()).isTrue()
    }

    @Test
    fun driveIsAvailableOnlyWhenItsAppResolves() {
        assertThat(DriveHandoff.isAvailable(context)).isFalse()
        installDrive()
        assertThat(DriveHandoff.isAvailable(context)).isTrue()
    }

    @Test
    fun handOffStartsDriveWhenTheFileHasContent() {
        installDrive()
        val file = backupFile()

        val opened = DriveHandoff.handOff(context, file, "application/json")

        assertThat(opened).isTrue()
        val started = shadowOf(app).nextStartedActivity
        assertThat(started.action).isEqualTo(Intent.ACTION_SEND)
        assertThat(started.`package`).isEqualTo(DriveHandoff.PACKAGE)
    }

    @Test
    fun anEmptyOrMissingFileIsNeverHandedOver() {
        installDrive()
        val empty = backupFile("")

        assertThat(DriveHandoff.isReady(empty)).isFalse()
        assertThat(DriveHandoff.handOff(context, empty, "application/json")).isFalse()
        assertThat(DriveHandoff.handOff(context, File(empty.parentFile, "missing.json"), "application/json")).isFalse()
        assertThat(shadowOf(app).nextStartedActivity).isNull()
    }

    @Test
    fun withoutTheDriveAppHandOffFailsSoTheCallerCanUseTheFilePicker() {
        shadowOf(app).checkActivities(true) // an unresolvable intent throws ActivityNotFoundException
        val file = backupFile()

        assertThat(DriveHandoff.isAvailable(context)).isFalse()
        assertThat(DriveHandoff.handOff(context, file, "application/json")).isFalse()
        assertThat(DriveHandoff.restoreStartUri(context)).isNull()
    }

    @Test
    fun theRestorePickerStartsInDriveWhenItIsInstalled() {
        installDrive()

        val intent = OpenDriveDocument().createIntent(context, arrayOf("application/json"))

        assertThat(intent.action).isEqualTo(Intent.ACTION_OPEN_DOCUMENT)
        val start = intent.getParcelableExtra<android.net.Uri>(android.provider.DocumentsContract.EXTRA_INITIAL_URI)
        assertThat(start?.authority).isEqualTo(DriveHandoff.STORAGE_AUTHORITY)
    }

    @Test
    fun aBackupFileIsKeptOnlyWhenItIsNotEmpty() {
        val dir = File(context.cacheDir, DriveHandoff.DIR).apply { mkdirs() }
        val target = File(dir, "out.json")

        val result = SafeOutput.writeFile<Unit>(target) { it.write("data".toByteArray()) }
        assertThat(result.copied).isTrue()
        assertThat(target.readText()).isEqualTo("data")
        assertThat(File(dir, "out.json.part").exists()).isFalse()

        target.delete()
        assertThrows(IOException::class.java) { SafeOutput.writeFile<Unit>(target) { } }
        assertThat(target.exists()).isFalse()
        assertThat(File(dir, "out.json.part").exists()).isFalse()

        val skipped = SafeOutput.writeFile(target, keep = { false }) { it.write("x".toByteArray()) }
        assertThat(skipped.copied).isFalse()
        assertThat(target.exists()).isFalse()
    }

    @Test
    fun noInternetPermissionIsDeclaredAnywhere() {
        // the merged manifest the app is built with (its own and every library's)
        @Suppress("DEPRECATION")
        val requested = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().toList()
        assertThat(requested).doesNotContain("android.permission.INTERNET")

        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val nodes = doc.getElementsByTagName("uses-permission")
        val own = (0 until nodes.length).map { (nodes.item(it) as org.w3c.dom.Element).getAttribute("android:name") }
        assertThat(own).doesNotContain("android.permission.INTERNET")
        // the Drive app is only looked up (package visibility), never contacted by uMessage
        val packages = doc.getElementsByTagName("package")
        val queried = (0 until packages.length)
            .map { (packages.item(it) as org.w3c.dom.Element).getAttribute("android:name") }
        assertThat(queried).contains(DriveHandoff.PACKAGE)
    }
}
