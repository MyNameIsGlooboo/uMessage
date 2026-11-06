package com.glooboo.umessage.helpers

import android.content.Context
import com.glooboo.umessage.models.ExportSummary
import com.glooboo.umessage.models.ImportSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one backup or restore that may run at a time, shared by [com.glooboo.umessage.services.BackupService] (which
 * does the work) and the dialogs (which show it). The work keeps running when the dialog or the app is closed.
 *
 * A run that the system kills part way leaves a mark in the preferences; [takeInterruptedRun] reports it once, so
 * the user learns that the last restore did not finish (running it again only adds what is still missing).
 */
object BackupJob {
    enum class Kind { IMPORT, EXPORT }

    sealed interface State {
        data object Idle : State

        /** [fraction] is null while the size of the work is unknown. */
        data class Running(val kind: Kind, val count: Int, val fraction: Float?) : State

        data class ImportDone(val summary: ImportSummary) : State

        /** [error] is set when the file could not be written at all. */
        data class ExportDone(val summary: ExportSummary, val error: String? = null) : State
    }

    private val mutableState = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = mutableState.asStateFlow()

    @Volatile var cancelRequested = false
        private set

    val isRunning get() = state.value is State.Running

    /** Claims the job slot; false when another run is going. */
    @Synchronized
    fun start(context: Context, kind: Kind): Boolean {
        if (isRunning) return false
        cancelRequested = false
        mutableState.value = State.Running(kind, 0, null)
        prefs(context).edit().putString(KEY_RUNNING, kind.name).apply()
        return true
    }

    fun update(kind: Kind, count: Int, fraction: Float?) {
        if (isRunning) mutableState.value = State.Running(kind, count, fraction?.coerceIn(0f, 1f))
    }

    fun finish(context: Context, result: State) {
        prefs(context).edit().remove(KEY_RUNNING).apply()
        mutableState.value = result
    }

    @Volatile private var stagedPassword: CharArray? = null

    /**
     * Hands the file password of the next run to the service through memory only: it is not written to the
     * preferences or put in an intent, and [takePassword] clears it.
     */
    fun stagePassword(password: CharArray?) {
        stagedPassword = password
    }

    /** The staged password, once; null when none was staged (for instance after the app was restarted). */
    @Synchronized
    fun takePassword(): CharArray? = stagedPassword.also { stagedPassword = null }

    fun requestCancel() {
        cancelRequested = true
    }

    /** Forgets a finished result once the user has seen it. */
    fun acknowledge() {
        if (!isRunning) mutableState.value = State.Idle
    }

    /** The kind of a run the system stopped before it finished, reported once; null when there is none. */
    fun takeInterruptedRun(context: Context): Kind? {
        if (isRunning) return null
        val prefs = prefs(context)
        val kind = prefs.getString(KEY_RUNNING, null)?.let { runCatching { Kind.valueOf(it) }.getOrNull() }
        prefs.edit().remove(KEY_RUNNING).apply()
        return kind
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val PREFS = "backup_job"
    private const val KEY_RUNNING = "running"
}
