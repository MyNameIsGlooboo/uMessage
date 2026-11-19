package com.glooboo.umessage.backup

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamRule
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.spam.data.DefaultSpamRepository
import com.glooboo.umessage.spam.data.PendingSmsEntity
import com.glooboo.umessage.spam.data.SpamDatabase
import com.glooboo.umessage.spam.data.SpamPlatform
import com.glooboo.umessage.spam.data.SpamSettingsStore
import com.glooboo.umessage.spam.engine.NumberNormalizer
import com.glooboo.umessage.testing.SpamTestWiring
import com.glooboo.umessage.ui.spam.AndroidBlockingMigrationStore
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.fossify.commons.extensions.baseConfig
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Restoring an older backup through the real SettingsBackup.read and apply, with the real rule database. */
@RunWith(AndroidJUnit4::class)
class BackupKeywordsRestoreTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var db: SpamDatabase
    private lateinit var repo: DefaultSpamRepository

    private object NoPlatform : SpamPlatform {
        override fun notifyBlocked(message: BlockedMessage) = Unit
        override fun deliverRestored(pending: PendingSmsEntity): Long? = 1L
    }

    @Before
    fun setUp() {
        db = SpamTestWiring.freshSpamDatabase(app)
        repo = DefaultSpamRepository(NoPlatform, db, SpamSettingsStore(app), NumberNormalizer { "US" })
        UMessage.repositoryFactory = { repo }
        app.config.blockedKeywords = emptySet()
        app.baseConfig.blockUnknownNumbers = true
    }

    @After
    fun tearDown() {
        SpamTestWiring.uninstall()
        SpamTestWiring.clearSpamDatabase()
    }

    private fun keywordRule(word: String, sim: Int? = null) =
        SpamRule(kind = ListKind.BLOCK, type = RuleType.KEYWORD, pattern = word, subscriptionId = sim)

    private fun backupOf(words: List<String>): Uri {
        val content = BackupContent(emptyList(), emptyList(), BackupSettings(), blockedKeywords = words)
        val file = File(app.cacheDir, "old-backup.json")
        file.writeText(SettingsBackupFormat.encode(content, 1))
        return Uri.fromFile(file)
    }

    private fun blockWords(): List<Pair<String, Int?>> = runBlocking {
        repo.rules.first()
            .filter { it.kind == ListKind.BLOCK && it.type == RuleType.KEYWORD }
            .map { it.pattern to it.subscriptionId }
    }

    /**
     * Since schema 9 (provider truth, 7.2) a rule chosen for one SIM is stored for both, so the old word is already a
     * both-SIM rule: it is not new, nothing is lost for the other SIM, and no duplicate is made.
     */
    @Test
    fun aWordWithARuleForOneSimOnlyIsNotLostForTheOtherSim() = runBlocking<Unit> {
        repo.addRule(keywordRule("prize", sim = 1))
        val (preview, failure) = SettingsBackup.read(app, backupOf(listOf("prize", "free gift")))
        assertThat(failure).isNull()
        assertThat(preview!!.newKeywords).containsExactly("free gift")

        val result = SettingsBackup.apply(app, preview)

        assertThat(result.ok).isTrue()
        assertThat(result.keywordsAdded).isEqualTo(preview.newKeywords.size)
        assertThat(blockWords()).containsExactly("prize" to null, "free gift" to null)
        assertThat(app.config.rawBlockedKeywords()).isEmpty()
    }

    @Test
    fun aWordWithABothSimRuleIsNotNewAndMakesNoDuplicate() = runBlocking<Unit> {
        repo.addRule(keywordRule("prize"))
        val (preview, _) = SettingsBackup.read(app, backupOf(listOf("prize", "free gift")))
        assertThat(preview!!.newKeywords).containsExactly("free gift")

        val result = SettingsBackup.apply(app, preview)

        assertThat(result.keywordsAdded).isEqualTo(1)
        assertThat(blockWords()).containsExactly("prize" to null, "free gift" to null)
    }

    @Test
    fun clearingTheOldSwitchRemembersThatItWasOn() {
        val store = AndroidBlockingMigrationStore(app)
        app.config.blockUnknownSwitchWasOn = false
        app.baseConfig.blockUnknownNumbers = true

        store.clearBlockUnknownSwitch()

        assertThat(app.config.blockUnknownSwitchWasOn).isTrue()
        assertThat(app.baseConfig.blockUnknownNumbers).isFalse()
    }
}
