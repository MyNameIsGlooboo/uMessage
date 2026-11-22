package com.glooboo.umessage.backup

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.helpers.MessagesImporter
import com.glooboo.umessage.helpers.MessagesWriter
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.MmsMedia
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.spam.UnknownInboxNotify
import com.glooboo.umessage.spam.UnknownSenderMode
import com.glooboo.umessage.spam.data.DefaultSpamRepository
import com.glooboo.umessage.spam.data.PendingSmsEntity
import com.glooboo.umessage.spam.data.SpamDatabase
import com.glooboo.umessage.spam.data.SpamPlatform
import com.glooboo.umessage.spam.data.SpamProviderMigration
import com.glooboo.umessage.spam.data.SpamSettingsStore
import com.glooboo.umessage.spam.engine.NumberNormalizer
import com.glooboo.umessage.spam.engine.SpamEngine
import com.glooboo.umessage.testing.FakeMessageStore
import com.glooboo.umessage.testing.SpamTestWiring
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.glooboo.umessage.testing.resetSortingState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * J13 (upgrade safety): backup files in the exact shape each released version wrote them, restored by this version
 * through the real readers (SettingsBackup.read and apply, MessagesImporter) and the real rule database.
 *
 * Settings backups ("kind": "settings-backup", "version": 1 in every release) came in three shapes: 0.3.0 to 0.4.6
 * (rules, forward rules, settings with `otpExemption`), 0.4.7 to 0.4.12 (plus `blockedKeywords`), 0.4.13 and 0.4.14
 * (plus `"modeIncludesSwitch": true`). No release wrote a rule's SIM. Messages backups have carried the held Spam
 * entries (`"backupType": "umessage_blocked"`) unchanged since 0.4.2.
 *
 * These are hand-written from the old encoders (git show of each release), not files exported by a release build;
 * that stays an emulator check (see the J13 review). Made-up data, 555 numbers only.
 */
@RunWith(AndroidJUnit4::class)
class ReleasedBackupFormatsRestoreTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var db: SpamDatabase
    private lateinit var repo: DefaultSpamRepository
    private val originalSweep = SpamProviderMigration.sweepHelpers

    private object NoPlatform : SpamPlatform {
        override fun notifyBlocked(message: BlockedMessage) = Unit
        override fun deliverRestored(pending: PendingSmsEntity): Long? = 1L
    }

    @Before
    fun setUp() {
        // FirstRun reads this test's own (empty) state: no first run yet, as right after the update.
        resetSortingState()
        db = SpamTestWiring.freshSpamDatabase(app)
        repo = DefaultSpamRepository(NoPlatform, db, SpamSettingsStore(app), NumberNormalizer { "US" })
        UMessage.repositoryFactory = { repo }
        SpamProviderMigration.sweepHelpers = {}
        app.config.blockedKeywords = emptySet()
    }

    @After
    fun tearDown() {
        SpamProviderMigration.sweepHelpers = originalSweep
        resetSharedMessagesDatabase()
        SpamTestWiring.uninstall()
        SpamTestWiring.clearSpamDatabase()
    }

    private fun file(text: String): Uri {
        val file = File(app.cacheDir, "released-backup.json")
        file.writeText(text)
        return Uri.fromFile(file)
    }

    private fun restore(text: String): SettingsBackup.Result = runBlocking {
        val (preview, failure) = SettingsBackup.read(app, file(text))
        assertThat(failure).isNull()
        SettingsBackup.apply(app, preview!!)
    }

    private fun rules() = runBlocking { repo.rules.first() }

    @Test
    fun aSettingsBackupOf030To046Restores() {
        // The phone already ran the one-time step and the user chose 3 days; the file says 14.
        SpamSettingsStore(app).applyProviderTruthMapping()
        SpamSettingsStore(app).update { it.copy(purgeAfterDays = 3) }

        val result = restore(OLDEST_SETTINGS)

        assertThat(result.ok).isTrue()
        assertThat(rules().map { Triple(it.kind, it.type, it.pattern) }).containsExactly(
            Triple(ListKind.BLOCK, RuleType.KEYWORD, "lottery"),
            Triple(ListKind.BLOCK, RuleType.NUMBER, SPAMMER),
            Triple(ListKind.ALLOW, RuleType.SENDER_ID, "MYBANK"),
        )
        assertThat(rules().map { it.subscriptionId }.toSet()).containsExactly(null)
        val settings = SpamSettingsStore(app).get()
        // Off on a phone that never had the old switch on: the filter stays off.
        assertThat(settings.mode).isEqualTo(UnknownSenderMode.OFF)
        assertThat(settings.filterOn).isFalse()
        assertThat(settings.notifyOnBlock).isTrue()
        assertThat(settings.unknownInboxNotify).isEqualTo(UnknownInboxNotify.SILENT)
        assertThat(settings.openLinkAction).isTrue()
        // I4/Q38: auto-delete is never taken from a file: the phone's 3 days stay, also in the stored value.
        assertThat(settings.purgeAfterDays).isEqualTo(3)
        val stored = app.getSharedPreferences("spamless_spam_settings", Context.MODE_PRIVATE)
        assertThat(stored.getInt("purge_after_days", -1)).isEqualTo(3)
        // H1: a forward rule comes back switched off and without a SIM, so nothing is forwarded unasked.
        val forward = SpamEngine.forwardRepository(app).currentRules().single()
        assertThat(forward.targets).containsExactly(TARGET)
        assertThat(forward.enabled).isFalse()
        assertThat(forward.subscriptionId).isNull()
    }

    @Test
    fun aSettingsBackupOf047To0412WithTheOldSwitchOnRestoresAsOn() {
        // This phone had the old "Block unknown numbers" switch on, so Off in an older file acted as Strict.
        app.config.blockUnknownSwitchWasOn = true

        val result = restore(MIDDLE_SETTINGS)

        assertThat(result.ok).isTrue()
        assertThat(SpamSettingsStore(app).get().mode).isEqualTo(UnknownSenderMode.STRICT)
        assertThat(SpamSettingsStore(app).get().filterOn).isTrue()
        // The old blocked words of the file become block word rules for both SIMs.
        assertThat(rules().filter { it.type == RuleType.KEYWORD }.map { it.pattern to it.subscriptionId })
            .containsExactly("free prize" to null)
        assertThat(app.config.rawBlockedKeywords()).isEmpty()
    }

    @Test
    fun aSettingsBackupOf0413Or0414Restores() {
        val result = restore(NEWEST_SETTINGS)

        assertThat(result.ok).isTrue()
        val settings = SpamSettingsStore(app).get()
        assertThat(settings.mode).isEqualTo(UnknownSenderMode.STRICT)
        assertThat(settings.filterOn).isTrue()
        assertThat(rules().map { it.pattern }).containsExactly(SPAMMER, "win")
        assertThat(rules().map { it.subscriptionId }.toSet()).containsExactly(null)
    }

    /**
     * Held Spam of a messages backup (0.4.2 to 0.4.14): back in the held table, waiting for the first run's message
     * migration (section 9, S16); never in the inbox. The SMS entry next to it goes to the phone's store as usual.
     */
    @Test
    fun heldSpamOfAMessagesBackupComesBackAndWaitsForTheMigration() {
        val store = FakeMessageStore.install()
        val importer = MessagesImporter(app, MessagesWriter(app) { emptyList() })

        val summary = importer.import(MESSAGES_WITH_HELD_SPAM.byteInputStream(), MessagesImporter.Options(true, true))

        assertThat(summary.damaged).isEqualTo(0)
        assertThat(store.rows("sms").map { it["body"] }).containsExactly("See you at six")
        val held = db.spamDao().getAllBlocked().map { it.toModel() }.sortedBy { it.receivedAt }
        assertThat(held.map { it.address }).containsExactly(SPAMMER, STRANGER).inOrder()
        assertThat(held[0].body).isEqualTo("Win a prize")
        assertThat(held[0].reason).isEqualTo(DecisionReason.BLOCK_RULE)
        assertThat(held[1].isMms).isTrue()
        assertThat(held[1].media).isEqualTo(MmsMedia.MISSING)
        assertThat(held.map { it.migratedMsgId }.toSet()).containsExactly(null)

        // Restoring the same file again adds nothing twice.
        importer.import(MESSAGES_WITH_HELD_SPAM.byteInputStream(), MessagesImporter.Options(true, true))
        assertThat(db.spamDao().getAllBlocked()).hasSize(2)
    }

    private companion object {
        const val SPAMMER = "+15550009876"
        const val STRANGER = "+15550001299"
        const val FRIEND = "+15550001234"
        const val TARGET = "+15550004321"

        /** SettingsBackupFormat.encode of 0.3.0 to 0.4.6: no blockedKeywords, no modeIncludesSwitch. */
        val OLDEST_SETTINGS = """
            {
                "app": "spamless", "kind": "settings-backup", "version": 1, "exportedAt": 1730000000000,
                "appVersion": "0.3.0",
                "rules": [
                    { "kind": "BLOCK", "type": "KEYWORD", "pattern": "lottery", "note": "", "createdAt": 100 },
                    { "kind": "BLOCK", "type": "NUMBER", "pattern": "$SPAMMER", "note": "@spamless:blocked_by_you",
                      "createdAt": 200 },
                    { "kind": "ALLOW", "type": "SENDER_ID", "pattern": "MYBANK", "note": "My bank", "createdAt": 300 }
                ],
                "forwardRules": [
                    { "senderType": "SENDER_ID", "senderPattern": "MYBANK", "bodyType": null, "bodyPattern": "",
                      "windowStart": null, "windowEnd": null, "targets": ["$TARGET"], "sendFrom": "SIM2",
                      "includeCodes": true, "enabled": true, "note": "", "createdAt": 400 }
                ],
                "settings": {
                    "spamMode": "OFF", "otpExemption": true, "purgeAfterDays": 14, "notifyOnBlock": true,
                    "unknownInboxNotify": "SILENT", "openLinkAction": true, "sendOnEnter": false,
                    "dailyForwardLimit": 20
                }
            }
        """.trimIndent()

        /** 0.4.7 to 0.4.12: the old blocked words went along; the mode did not count the old switch yet. */
        val MIDDLE_SETTINGS = """
            {
                "app": "spamless", "kind": "settings-backup", "version": 1, "exportedAt": 1731000000000,
                "appVersion": "0.4.9",
                "rules": [],
                "forwardRules": [],
                "blockedKeywords": ["free prize"],
                "settings": { "spamMode": "OFF", "purgeAfterDays": 30, "notifyOnBlock": false }
            }
        """.trimIndent()

        /**
         * 0.4.13 and 0.4.14: the mode says what blocks (the old switch was folded into it). `fromALaterVersion` is a
         * deliberate unknown field (no release wrote it): readers must skip what they do not know.
         */
        val NEWEST_SETTINGS = """
            {
                "app": "spamless", "kind": "settings-backup", "version": 1, "exportedAt": 1731900000000,
                "modeIncludesSwitch": true, "appVersion": "0.4.14",
                "rules": [
                    { "kind": "BLOCK", "type": "NUMBER", "pattern": "$SPAMMER", "note": "", "createdAt": 100,
                      "fromALaterVersion": 4 },
                    { "kind": "BLOCK", "type": "DOMAIN", "pattern": "win", "note": "", "createdAt": 200 }
                ],
                "forwardRules": [],
                "settings": { "spamMode": "STRICT", "purgeAfterDays": 7, "copyCodeOnTap": true }
            }
        """.trimIndent()

        /** A messages backup of 0.4.2 to 0.4.14: an SMS, a held SMS and a held MMS whose content was not kept. */
        val MESSAGES_WITH_HELD_SPAM = """
            [
                {"subscriptionId": -1, "address": "$FRIEND", "body": "See you at six", "date": 1700000001000,
                 "dateSent": 1700000001000, "locked": 0, "protocol": null, "read": 1, "status": -1, "type": 1,
                 "serviceCenter": null, "backupType": "sms"},
                {"address": "$SPAMMER", "body": "Win a prize", "mms": false, "received_at": 1700000002000,
                 "reason": "BLOCK_RULE", "media": "NONE", "backupType": "umessage_blocked"},
                {"address": "$STRANGER", "body": "", "mms": true, "received_at": 1700000003000,
                 "reason": "UNKNOWN_SENDER", "media": "MISSING", "backupType": "umessage_blocked"}
            ]
        """.trimIndent()
    }
}
