package com.glooboo.umessage.services

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.BackupCrypto
import com.glooboo.umessage.helpers.BackupJob
import com.glooboo.umessage.helpers.BackupSummaryText
import com.glooboo.umessage.testing.FakeMessageStore
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.io.File

/** The restore runs in a foreground service with a notification, and its result reaches the dialog and the user. */
@RunWith(AndroidJUnit4::class)
class BackupServiceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        BackupJob.acknowledge()
        resetSharedMessagesDatabase()
    }

    private fun waitForResult(): BackupJob.State {
        val deadline = System.currentTimeMillis() + 10_000
        while (BackupJob.isRunning && System.currentTimeMillis() < deadline) Thread.sleep(20)
        return BackupJob.state.value
    }

    @Test
    fun restore_runsInTheForegroundAndPostsItsSummary() {
        val store = FakeMessageStore.install()
        val backup = File(context.cacheDir, "backup.json").apply {
            writeText(
                """[{"subscriptionId":-1,"address":"+15550001","body":"hi","date":1,"dateSent":1,"locked":0,""" +
                    """"protocol":null,"read":1,"status":-1,"type":1,"serviceCenter":null,"backupType":"sms"}]"""
            )
        }
        val intent = Intent(context, BackupService::class.java)
            .setAction("com.glooboo.spamless.action.IMPORT_MESSAGES")
            .setData(Uri.fromFile(backup))
        val controller = Robolectric.buildService(BackupService::class.java, intent).create()
        controller.startCommand(0, 1)

        val service = controller.get()
        assertThat(shadowOf(service).lastForegroundNotification).isNotNull()

        val result = waitForResult()
        assertThat(result).isInstanceOf(BackupJob.State.ImportDone::class.java)
        assertThat((result as BackupJob.State.ImportDone).summary.added).isEqualTo(1)
        assertThat(store.count("sms")).isEqualTo(1)
        assertThat(BackupJob.takeInterruptedRun(context)).isNull()

        val notifications = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications
        assertThat(notifications.map { it.extras.getString("android.title") }).contains("Restore finished")
    }

    private val smsJson = (
        """[{"subscriptionId":-1,"address":"+15550001","body":"secret hi","date":1,"dateSent":1,"locked":0,""" +
            """"protocol":null,"read":1,"status":-1,"type":1,"serviceCenter":null,"backupType":"sms"}]"""
        )

    private fun protectedFile(password: String): File = File(context.cacheDir, "backup.umsg").apply {
        outputStream().use { raw ->
            val sink = BackupCrypto.encrypt(raw, password.toCharArray(), iterations = 1_000)
            sink.use { it.write(smsJson.toByteArray()) }
        }
    }

    private fun restore(file: File, password: String?): BackupJob.State.ImportDone {
        BackupJob.stagePassword(password?.toCharArray())
        val intent = Intent(context, BackupService::class.java)
            .setAction("com.glooboo.spamless.action.IMPORT_MESSAGES")
            .setData(Uri.fromFile(file))
        Robolectric.buildService(BackupService::class.java, intent).create().startCommand(0, 1)
        return waitForResult() as BackupJob.State.ImportDone
    }

    @Test
    fun protectedRestore_withTheRightPassword_restoresTheMessages() {
        val store = FakeMessageStore.install()
        val summary = restore(protectedFile("open sesame"), "open sesame").summary
        assertThat(summary.added).isEqualTo(1)
        assertThat(summary.wrongPassword).isFalse()
        assertThat(store.count("sms")).isEqualTo(1)
        assertThat(BackupJob.takePassword()).isNull()
    }

    @Test
    fun protectedRestore_withAWrongPassword_saysSoAndWritesNothing() {
        val store = FakeMessageStore.install()
        val done = restore(protectedFile("open sesame"), "not it")
        assertThat(done.summary.wrongPassword).isTrue()
        assertThat(done.summary.added).isEqualTo(0)
        assertThat(store.count("sms")).isEqualTo(0)
        assertThat(BackupSummaryText.import(context, done.summary)).contains("password")
    }

    @Test
    fun protectedRestore_withoutAPassword_saysSoAndWritesNothing() {
        val store = FakeMessageStore.install()
        val done = restore(protectedFile("open sesame"), null)
        assertThat(done.summary.wrongPassword).isTrue()
        assertThat(store.count("sms")).isEqualTo(0)
    }

    @Test
    fun protectedExport_thenRestore_roundTripsThroughTheService() {
        val source = FakeMessageStore.install()
        source.addSms("+15550001", "secret hi", 1_000)
        val target = File(context.cacheDir, "out.umsg").apply { writeText("old") }
        BackupJob.stagePassword("pw 123".toCharArray())
        val intent = Intent(context, BackupService::class.java)
            .setAction("com.glooboo.spamless.action.EXPORT_MESSAGES")
            .setData(Uri.fromFile(target))
            .putExtra("protected", true)
        Robolectric.buildService(BackupService::class.java, intent).create().startCommand(0, 1)
        val done = waitForResult() as BackupJob.State.ExportDone
        assertThat(done.error).isNull()
        assertThat(done.summary.sms).isEqualTo(1)
        val bytes = target.readBytes()
        assertThat(String(bytes, Charsets.ISO_8859_1)).doesNotContain("secret hi")
        assertThat(BackupCrypto.isProtected(bytes.inputStream().buffered())).isTrue()

        BackupJob.acknowledge()
        val restored = FakeMessageStore.install()
        assertThat(restore(target, "pw 123").summary.added).isEqualTo(1)
        assertThat(restored.rows("sms").single()["body"]).isEqualTo("secret hi")
    }

    @Test
    fun protectedExport_whoseStagedPasswordIsLost_failsInsteadOfWritingAPlainFile() {
        FakeMessageStore.install().addSms("+15550001", "secret hi", 1_000)
        val target = File(context.cacheDir, "lost.umsg").apply { writeText("old") }
        val intent = Intent(context, BackupService::class.java)
            .setAction("com.glooboo.spamless.action.EXPORT_MESSAGES")
            .setData(Uri.fromFile(target))
            .putExtra("protected", true)
        Robolectric.buildService(BackupService::class.java, intent).create().startCommand(0, 1)
        val done = waitForResult() as BackupJob.State.ExportDone
        assertThat(done.error).isNotNull()
        assertThat(target.readText()).doesNotContain("secret hi")
    }

    @Test
    fun secondStartWhileRunning_doesNotStartAnotherRun() {
        FakeMessageStore.install()
        assertThat(BackupJob.start(context, BackupJob.Kind.EXPORT)).isTrue()
        assertThat(BackupJob.start(context, BackupJob.Kind.IMPORT)).isFalse()
        BackupJob.finish(context, BackupJob.State.Idle)
    }
}
