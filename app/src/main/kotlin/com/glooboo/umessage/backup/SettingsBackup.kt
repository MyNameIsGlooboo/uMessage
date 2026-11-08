package com.glooboo.umessage.backup

import android.content.Context
import android.database.SQLException
import android.net.Uri
import com.glooboo.umessage.BuildConfig
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.helpers.DateStyle
import com.glooboo.umessage.helpers.NavPrefs
import com.glooboo.umessage.helpers.TextSize
import com.glooboo.umessage.helpers.ThreadPrefs
import com.glooboo.umessage.helpers.W3aPrefs
import com.glooboo.umessage.helpers.W3cPrefs
import com.glooboo.umessage.spam.ForwardingAvailability
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.SpamSettings
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.spam.engine.SpamEngine
import com.glooboo.umessage.ui.spam.ForwardOwnNumbers
import com.glooboo.umessage.ui.theme.ThemeChoice
import com.glooboo.umessage.ui.theme.ThemeColours
import com.glooboo.umessage.ui.theme.ThemeMode
import com.glooboo.umessage.ui.theme.ThemePresets
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Backup and restore of the allow/block rules, the forward rules and every setting of the app (spam settings, bottom
 * bar, dates, text size, theme, sending, notifications, cleanup, privacy and forwarding limits) to one JSON file
 * (ID-38, N09), so the file can move a user to a new install. See [SettingsBackupFormat] for the file and
 * [BackupMoreSettings] for the settings added later.
 *
 * Never part of a backup: message text, the app-lock PIN and lock state (a restore does not touch them), and values
 * tied to this phone: SIM ids and names (a rule's SIM scope is dropped on import), saved texts, hidden chats, mute
 * times and dismissed cards.
 *
 * A restore is two steps: [read] checks the file and says what it would change, [apply] does it. Import only adds:
 * rules and forward rules already on the phone are left as they are and duplicates are skipped; settings present in
 * the file replace the phone's. Forward rules come in switched off with checked targets (H1), and "Pause all
 * forwarding" is only ever turned on. The caller (Settings) asks for the app-lock check before [read].
 */
object SettingsBackup {

    /** Why an import did nothing. */
    enum class Failure {
        /** The file could not be opened or read, or is larger than [MAX_BYTES]. */
        UNREADABLE,

        /** The file is not a uMessage settings backup. */
        NOT_A_BACKUP,
    }

    /**
     * A checked backup, ready for [apply]: [content] as read, the forward rules that would be added ([forwards]) and
     * the settings that differ from the phone's ([changedSettings], keys of [BackupSettings]).
     */
    data class Preview(
        val content: BackupContent,
        val forwards: ForwardImport,
        val changedSettings: List<String>,
    ) {
        val allowRules: Int get() = content.rules.count { it.kind == ListKind.ALLOW }
        val blockRules: Int get() = content.rules.size - allowRules
    }

    /**
     * What an import did. [failure] is null on success; the counts are then the rules and forward rules added and
     * the ones skipped (already present, or not usable). Every added forward rule is switched off.
     */
    data class Result(
        val rulesAdded: Int = 0,
        val rulesSkipped: Int = 0,
        val forwardRulesAdded: Int = 0,
        val forwardRulesSkipped: Int = 0,
        val settingsApplied: Boolean = false,
        val failure: Failure? = null,
    ) {
        val ok: Boolean get() = failure == null
    }

    /** Largest backup file read. */
    const val MAX_BYTES = 5 * 1024 * 1024

    private const val BUFFER_BYTES = 8192

    /**
     * Writes the backup to [uri] (replacing its content) and returns how many rules plus forward rules it holds.
     * Throws [IOException] when the file cannot be written.
     */
    suspend fun export(context: Context, uri: Uri): Int = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val repo = UMessage.repository(app)
        val content = BackupContent(
            rules = repo.rules.first(),
            // Forward rules are part of the backup when this build has forwarding.
            forwardRules = if (ForwardingAvailability.isAvailable(app)) {
                SpamEngine.forwardRepository(app).rules.first()
            } else {
                emptyList()
            },
            settings = collectSettings(app, repo.settings.first()),
            appVersion = BuildConfig.VERSION_NAME,
        )
        val text = SettingsBackupFormat.encode(content, System.currentTimeMillis())
        SafeOutput.write<Unit>(app, uri) { it.write(text.toByteArray(Charsets.UTF_8)) }
        content.rules.size + content.forwardRules.size
    }

    /** Reads and checks the backup at [uri] without changing anything. Never throws; a [Failure] when unusable. */
    suspend fun read(context: Context, uri: Uri): Pair<Preview?, Failure?> = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val text = readFile(app, uri) ?: return@withContext null to Failure.UNREADABLE
        val content = try {
            SettingsBackupFormat.decode(text)
        } catch (_: BackupFormatException) {
            return@withContext null to Failure.NOT_A_BACKUP
        }
        try {
            val repo = UMessage.repository(app)
            // A backup made with forwarding (from another build) is read, but its forward rules are left out here.
            val forwards = SettingsBackupFormat.prepareForwardImport(
                incoming = if (ForwardingAvailability.isAvailable(app)) content.forwardRules else emptyList(),
                existing = SpamEngine.forwardRepository(app).currentRules(),
                ownNumbers = ForwardOwnNumbers.load(app),
            )
            val current = collectSettings(app, repo.settings.first())
            val changed = SettingsBackupFormat.changedSettings(current, content.settings)
            Preview(content, forwards, changed) to null
        } catch (_: IOException) {
            null to Failure.UNREADABLE
        } catch (_: SQLException) {
            null to Failure.UNREADABLE
        } catch (_: IllegalStateException) {
            null to Failure.UNREADABLE
        }
    }

    /** Adds the rules and forward rules (switched off) of [preview] and applies its settings. Never throws. */
    suspend fun apply(context: Context, preview: Preview): Result = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        try {
            val repo = UMessage.repository(app)
            val before = repo.rules.first().size
            repo.restoreRules(preview.content.rules)
            val rulesAdded = repo.rules.first().size - before

            val forwards = SpamEngine.forwardRepository(app)
            val forwardsAdded = if (ForwardingAvailability.isAvailable(app)) {
                preview.forwards.toAdd.count { runCatching { forwards.add(it) }.isSuccess }
            } else {
                0
            }

            val applied = applySettings(app, preview.content.settings)
            Result(
                rulesAdded = rulesAdded,
                rulesSkipped = (preview.content.rules.size - rulesAdded).coerceAtLeast(0),
                forwardRulesAdded = forwardsAdded,
                forwardRulesSkipped = if (ForwardingAvailability.isAvailable(app)) {
                    preview.content.forwardRules.size - forwardsAdded
                } else {
                    0
                },
                settingsApplied = applied,
            )
        } catch (_: IOException) {
            Result(failure = Failure.UNREADABLE)
        } catch (_: SQLException) {
            Result(failure = Failure.UNREADABLE)
        } catch (_: IllegalStateException) {
            Result(failure = Failure.UNREADABLE)
        }
    }

    private fun collectSettings(context: Context, spam: SpamSettings): BackupSettings {
        val nav = NavPrefs.get(context)
        val w3a = W3aPrefs.get(context)
        val w3c = W3cPrefs.get(context)
        val config = context.config
        val core = BackupSettings(
            spamMode = spam.mode,
            otpExemption = spam.otpExemption,
            purgeAfterDays = spam.purgeAfterDays,
            notifyOnBlock = spam.notifyOnBlock,
            showForwardedTab = nav.showForwardedTab,
            archivedIntroDismissed = nav.archivedIntroDismissed,
            useJalaliDates = ThreadPrefs.get(context).useJalaliDates,
            maskForwardedCodes = w3a.maskForwardedCodes,
            maskKeepLastDigits = w3a.maskKeepLastDigits,
            hideCodesOnLockScreen = w3a.hideCodesOnLockScreen,
            dailyForwardLimit = w3a.dailyForwardLimit,
            dateStyle = w3c.dateStyle.key,
            textSize = w3c.textSize.key,
            theme = ThemePresets.current(context).let { "${it.mode.name}/${it.colours.name}" },
            sendOnEnter = config.sendOnEnter,
            lockScreenVisibility = config.lockScreenVisibilitySetting,
            forwardPaused = nav.forwardPaused,
        )
        return BackupMoreSettings.collect(context, core, spam)
    }

    /** Applies what the file holds; returns whether anything was present. */
    private suspend fun applySettings(context: Context, s: BackupSettings): Boolean {
        if (s == BackupSettings()) return false
        UMessage.repository(context).updateSettings { current ->
            current.copy(
                mode = s.spamMode ?: current.mode,
                otpExemption = s.otpExemption ?: current.otpExemption,
                // I4/Q38: purgeAfterDays (blocked messages deleted after N days) is never taken from a file.
                notifyOnBlock = s.notifyOnBlock ?: current.notifyOnBlock,
                unknownInboxNotify = s.unknownInboxNotify ?: current.unknownInboxNotify,
                openLinkAction = s.openLinkAction ?: current.openLinkAction,
                blockOutsideRegion = s.blockOutsideRegion ?: current.blockOutsideRegion,
            )
        }
        BackupMoreSettings.apply(context, s)
        applyForwarding(context, s)
        applyDisplay(context, s)
        val config = context.config
        s.sendOnEnter?.let { config.sendOnEnter = it }
        s.lockScreenVisibility?.let { config.lockScreenVisibilitySetting = it }
        s.hideCodesOnLockScreen?.let { W3aPrefs.get(context).hideCodesOnLockScreen = it }
        return true
    }

    /** The Forwarded tab, code masking and the daily limit. A restore can stop forwarding, never start it. */
    private fun applyForwarding(context: Context, s: BackupSettings) {
        val nav = NavPrefs.get(context)
        s.showForwardedTab?.let { nav.showForwardedTab = it }
        s.archivedIntroDismissed?.let { nav.archivedIntroDismissed = it }
        if (s.forwardPaused == true) nav.forwardPaused = true
        val w3a = W3aPrefs.get(context)
        s.maskForwardedCodes?.let { w3a.maskForwardedCodes = it }
        s.maskKeepLastDigits?.let { w3a.maskKeepLastDigits = it }
        s.dailyForwardLimit?.let { w3a.dailyForwardLimit = it }
    }

    /** Dates, text size and theme. A date style wins over the older Jalali switch, which is kept in step (L2). */
    private fun applyDisplay(context: Context, s: BackupSettings) {
        val thread = ThreadPrefs.get(context)
        val w3c = W3cPrefs.get(context)
        val style = DateStyle.entries.firstOrNull { it.key == s.dateStyle }
        if (style != null) {
            w3c.dateStyle = style
            thread.useJalaliDates = style != DateStyle.GREGORIAN
        } else {
            s.useJalaliDates?.let {
                thread.useJalaliDates = it
                w3c.dateStyle = if (it) DateStyle.JALALI else DateStyle.GREGORIAN
            }
        }
        TextSize.entries.firstOrNull { it.key == s.textSize }?.let { w3c.textSize = it }
        themeChoice(s.theme)
            ?.takeIf { it.colours != ThemeColours.WALLPAPER || ThemePresets.wallpaperAvailable() }
            ?.let { ThemePresets.apply(context, it) }
    }

    /** "MODE/COLOURS" as written since Mode and Colours were split, or a single older look name ("SKY"). */
    internal fun themeChoice(saved: String?): ThemeChoice? {
        val parts = saved?.split('/') ?: return null
        if (parts.size == 2) {
            val mode = ThemeMode.entries.firstOrNull { it.name == parts[0] } ?: return null
            val colours = ThemePresets.coloursFromName(parts[1]) ?: return null
            return ThemeChoice(mode, colours)
        }
        return ThemePresets.legacyChoice(saved, Int.MAX_VALUE, ThemePresets.wallpaperAvailable())
    }

    private fun readFile(context: Context, uri: Uri): String? = try {
        context.contentResolver.openInputStream(uri)?.use { readLimited(it) }
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    /** The text of [input], or null when it is longer than [MAX_BYTES]. */
    private fun readLimited(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > MAX_BYTES) return null
            out.write(buffer, 0, n)
        }
        // D13: a file saved by a Windows editor starts with a byte order mark, which the JSON reader refuses.
        return out.toString(Charsets.UTF_8.name()).removePrefix("\uFEFF")
    }
}
