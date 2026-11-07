package com.glooboo.umessage.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.glooboo.umessage.R
import com.glooboo.umessage.backup.SafeOutput
import com.glooboo.umessage.activities.SettingsActivity
import com.glooboo.umessage.export.ExportScope
import com.glooboo.umessage.extensions.getConversationIds
import com.glooboo.umessage.extensions.getNameFromAddress
import com.glooboo.umessage.helpers.BackupCrypto
import com.glooboo.umessage.helpers.BackupJob
import com.glooboo.umessage.helpers.BackupJob.Kind
import com.glooboo.umessage.helpers.BackupSummaryText
import com.glooboo.umessage.helpers.ExportFormat
import com.glooboo.umessage.helpers.MessagesImporter
import com.glooboo.umessage.helpers.MessagesReader
import com.glooboo.umessage.models.ExportSummary
import com.glooboo.umessage.models.ImportSummary
import kotlin.concurrent.thread

/**
 * Runs a backup or restore as a foreground service, so it keeps going when the user leaves the app, with its
 * progress in a notification that also has a Stop button. The result is published through [BackupJob] for the
 * dialog and posted as a notification, so it is seen even when the dialog was closed.
 */
class BackupService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            BackupJob.requestCancel()
            if (!BackupJob.isRunning) stopSelf(startId)
            return START_NOT_STICKY
        }
        val uri = intent?.data
        val kind = when (intent?.action) {
            ACTION_IMPORT -> Kind.IMPORT
            ACTION_EXPORT -> Kind.EXPORT
            else -> null
        }
        if (uri == null || kind == null) {
            if (!BackupJob.isRunning) stopSelf(startId)
            return START_NOT_STICKY
        }
        // Started with startForegroundService: go to the foreground first, whatever happens next.
        createChannel()
        ServiceCompat.startForeground(
            this, PROGRESS_ID, progressNotification(kind, 0, null),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        // Taken before anything else can return, so a password never outlives its run.
        val password = BackupJob.takePassword()
        if (!BackupJob.start(this, kind)) {
            // The running job keeps the service. The file the picker just made for this request would stay at 0 bytes.
            if (kind == Kind.EXPORT) deleteQuietly(uri)
            return START_NOT_STICKY
        }
        val sms = intent.getBooleanExtra(EXTRA_SMS, true)
        val mms = intent.getBooleanExtra(EXTRA_MMS, true)
        val export = ExportRequest(
            format = ExportFormat.values().firstOrNull { it.name == intent.getStringExtra(EXTRA_FORMAT) }
                ?: ExportFormat.JSON,
            threadIds = intent.getLongArrayExtra(EXTRA_THREADS)?.toList(),
            withPassword = intent.getBooleanExtra(EXTRA_PROTECTED, false),
        )
        thread(name = "backup-$kind") {
            val result = try {
                if (kind == Kind.IMPORT) {
                    runImport(uri, sms, mms, password)
                } else {
                    runExport(uri, sms, mms, export, password.takeIf { export.withPassword })
                }
            } catch (e: Throwable) { // OutOfMemoryError included: report it instead of dying silently
                if (kind == Kind.IMPORT) {
                    BackupJob.State.ImportDone(ImportSummary().apply { invalidFile = true; addReason(e.toString()) })
                } else {
                    deleteQuietly(uri)
                    BackupJob.State.ExportDone(ExportSummary(), e.toString())
                }
            }
            BackupJob.finish(this, result)
            postResult(result)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Android 15 caps data-sync services; stop cleanly so what was done is kept and reported. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        BackupJob.requestCancel()
    }

    private fun runImport(uri: Uri, sms: Boolean, mms: Boolean, password: CharArray?): BackupJob.State {
        val size = fileSize(uri)
        val ticker = Ticker()
        val summary = contentResolver.openInputStream(uri)?.use { raw ->
            val buffered = raw.buffered()
            val input = try {
                if (BackupCrypto.isProtected(buffered)) {
                    BackupCrypto.decrypt(buffered, password ?: throw BackupCrypto.WrongPasswordException())
                } else {
                    buffered
                }
            } catch (_: BackupCrypto.WrongPasswordException) {
                return BackupJob.State.ImportDone(ImportSummary().apply { wrongPassword = true })
            } catch (_: BackupCrypto.DamagedFileException) {
                return BackupJob.State.ImportDone(ImportSummary().apply { invalidFile = true })
            } finally {
                password?.fill(' ')
            }
            MessagesImporter(this).import(
                input, MessagesImporter.Options(sms, mms),
                progress = { entries, bytes ->
                    val fraction = if (size > 0) bytes.toFloat() / size else null
                    BackupJob.update(Kind.IMPORT, entries, fraction)
                    if (ticker.due()) notifyProgress(Kind.IMPORT, entries, fraction)
                },
                isCancelled = { BackupJob.cancelRequested },
            )
        } ?: ImportSummary().apply { invalidFile = true }
        return BackupJob.State.ImportDone(summary)
    }

    /** What an export writes: the file [format], the conversations ([threadIds], all when null) and the password. */
    private class ExportRequest(val format: ExportFormat, val threadIds: List<Long>?, val withPassword: Boolean)

    private fun runExport(
        uri: Uri, sms: Boolean, mms: Boolean, request: ExportRequest, password: CharArray?,
    ): BackupJob.State {
        if (request.withPassword && password == null) {
            deleteQuietly(uri)
            return BackupJob.State.ExportDone(ExportSummary(), getString(R.string.export_password_lost))
        }
        val ticker = Ticker()
        val ids = ExportScope.exportable(this, request.threadIds ?: getConversationIds())
        val summary = try {
            // Produced into a temporary file and copied only when it holds a real backup, so the chosen file is never
            // truncated first and left empty when the work fails, is stopped, or finds nothing.
            val result = SafeOutput.write(this, uri, keep = { !it.cancelled && it.total > 0 }) { raw ->
                val output = if (password != null) BackupCrypto.encrypt(raw, password) else raw
                val written = MessagesReader(this).export(
                    output, sms, mms,
                    progress = { messages, done, total ->
                        val fraction = if (total > 0) done.toFloat() / total else null
                        BackupJob.update(Kind.EXPORT, messages, fraction)
                        if (ticker.due()) notifyProgress(Kind.EXPORT, messages, fraction)
                    },
                    isCancelled = { BackupJob.cancelRequested },
                    conversationIds = ids,
                    format = request.format,
                    contactName = ::savedName,
                    wholePhone = request.threadIds == null,
                )
                output.close() // seals the last chunk of a protected file
                written
            }
            result.value.also { if (!result.copied) deleteQuietly(uri) }
        } catch (e: Exception) {
            deleteQuietly(uri)
            return BackupJob.State.ExportDone(ExportSummary(), e.message ?: e.javaClass.simpleName)
        } finally {
            password?.fill(' ')
        }
        // Nothing to save is an error the user sees, never a finished backup (and never an empty file).
        if (!summary.cancelled && summary.total == 0) {
            return BackupJob.State.ExportDone(summary, getString(R.string.backup_export_nothing))
        }
        return BackupJob.State.ExportDone(summary)
    }

    /** The saved contact name of [address], or null when there is none or contacts cannot be read. */
    private fun savedName(address: String): String? =
        runCatching { getNameFromAddress(address, null) }.getOrNull()?.takeIf { it != address }

    private fun fileSize(uri: Uri): Long = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L
        } ?: -1L
    } catch (_: Exception) {
        -1L
    }

    private fun deleteQuietly(uri: Uri) {
        try {
            DocumentsContract.deleteDocument(contentResolver, uri)
        } catch (_: Exception) {
            // the error is already reported; a second message about the file would only confuse
        }
    }

    // ---- notifications --------------------------------------------------------------------------------------------

    private val notificationManager get() = getSystemService(NotificationManager::class.java)

    private fun createChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.backup_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun openSettings(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, SettingsActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun progressNotification(kind: Kind, count: Int, fraction: Float?): Notification {
        val percent = fraction?.let { (it * 100).toInt() }
        val text = when (kind) {
            Kind.IMPORT -> if (percent != null) {
                resources.getQuantityString(R.plurals.backup_progress_import_percent, count, count, percent)
            } else {
                resources.getQuantityString(R.plurals.backup_progress_import, count, count)
            }

            Kind.EXPORT -> if (percent != null) {
                resources.getQuantityString(R.plurals.backup_progress_export_percent, count, count, percent)
            } else {
                resources.getQuantityString(R.plurals.backup_progress_export, count, count)
            }
        }
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BackupService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_messenger)
            .setContentTitle(getString(if (kind == Kind.IMPORT) R.string.backup_restoring else R.string.backup_exporting))
            .setContentText(text)
            .setProgress(100, percent ?: 0, percent == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openSettings())
            .addAction(0, getString(R.string.backup_stop), stop)
            .build()
    }

    private fun notifyProgress(kind: Kind, count: Int, fraction: Float?) {
        if (!BackupJob.isRunning) return
        try {
            notificationManager.notify(PROGRESS_ID, progressNotification(kind, count, fraction))
        } catch (_: SecurityException) {
            // notifications are off; the dialog still shows progress
        }
    }

    private fun postResult(result: BackupJob.State) {
        val (title, text) = when (result) {
            is BackupJob.State.ImportDone ->
                BackupSummaryText.importTitle(this, result.summary) to BackupSummaryText.import(this, result.summary)

            is BackupJob.State.ExportDone -> BackupSummaryText.exportTitle(this, result.summary, result.error) to
                BackupSummaryText.export(this, result.summary, result.error)

            else -> return
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_messenger)
            .setContentTitle(title)
            .setContentText(text.lineSequence().firstOrNull())
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openSettings())
            .build()
        try {
            notificationManager.notify(RESULT_ID, notification)
        } catch (_: SecurityException) {
            // notifications are off; the dialog shows the result if it is still open
        }
    }

    /** Limits notification updates to a few a second; the system drops faster ones anyway. */
    private class Ticker {
        private var last = 0L
        fun due(): Boolean {
            val now = SystemClock.elapsedRealtime()
            if (now - last < 500) return false
            last = now
            return true
        }
    }

    companion object {
        private const val ACTION_IMPORT = "com.glooboo.spamless.action.IMPORT_MESSAGES"
        private const val ACTION_EXPORT = "com.glooboo.spamless.action.EXPORT_MESSAGES"
        private const val ACTION_CANCEL = "com.glooboo.spamless.action.CANCEL_BACKUP"
        private const val EXTRA_SMS = "sms"
        private const val EXTRA_MMS = "mms"
        private const val EXTRA_FORMAT = "format"
        private const val EXTRA_THREADS = "threads"
        private const val EXTRA_PROTECTED = "protected"
        private const val CHANNEL_ID = "backup_restore"
        private const val PROGRESS_ID = 0x5B_0001
        private const val RESULT_ID = 0x5B_0002

        /** A protected file needs its password staged in [BackupJob] first; the service takes it from there. */
        fun startImport(context: Context, uri: Uri, sms: Boolean, mms: Boolean) =
            start(context, ACTION_IMPORT, uri, sms, mms)

        /**
         * [threadIds] are the conversations to export, all when null. With [withPassword] the file is sealed with the
         * password staged in [BackupJob]; without one the export fails and says so.
         */
        fun startExport(
            context: Context, uri: Uri, sms: Boolean, mms: Boolean,
            format: ExportFormat = ExportFormat.JSON, threadIds: List<Long>? = null, withPassword: Boolean = false,
        ) = start(context, ACTION_EXPORT, uri, sms, mms) {
            putExtra(EXTRA_FORMAT, format.name)
            putExtra(EXTRA_PROTECTED, withPassword)
            if (threadIds != null) putExtra(EXTRA_THREADS, threadIds.toLongArray())
        }

        private fun start(
            context: Context, action: String, uri: Uri, sms: Boolean, mms: Boolean, extras: Intent.() -> Unit = {},
        ) {
            val intent = Intent(context, BackupService::class.java)
                .setAction(action)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                .putExtra(EXTRA_SMS, sms)
                .putExtra(EXTRA_MMS, mms)
                .apply(extras)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
