package com.glooboo.umessage.databases

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import java.io.File

/**
 * D11: a downgrade backup that cannot be restored is kept and tried again on every database open, which copies the
 * whole file to a scratch database each time. After [MAX_FAILURES] failed tries the backup is retired as `-failed`
 * (still on disk, never copied again), so a permanently broken one stops costing a file copy at every app start.
 */
internal object RecoveryAttempts {
    const val MAX_FAILURES = 5
    private const val PREFS = "database_recovery_attempts"

    /** Counts one more failed restore of [backup]; retires it after [MAX_FAILURES] of them. True when retired. */
    fun failed(context: Context, backup: File): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val count = prefs.getInt(backup.name, 0) + 1
        if (count < MAX_FAILURES) {
            prefs.edit { putInt(backup.name, count) }
            return false
        }
        prefs.edit { remove(backup.name) }
        DatabaseBackups.retire(backup, "failed")
        Log.w("DatabaseRecovery", "${backup.name} failed $count times: kept as -failed, not tried again")
        return true
    }

    /** Forgets the failures of [backup] after a restore worked. */
    fun succeeded(context: Context, backup: File) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { remove(backup.name) }
    }
}
