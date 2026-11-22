package com.glooboo.umessage

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.config
import com.glooboo.umessage.helpers.ShortcutHelper
import com.glooboo.umessage.spam.FirstRunProgressAdapter
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SortDryRun
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.spam.data.SpamDatabase
import com.glooboo.umessage.spam.engine.SpamEngine
import com.glooboo.umessage.testing.FakeMessageStore
import com.glooboo.umessage.testing.joinAppBackgroundThreads
import com.glooboo.umessage.testing.migrationTestHelper
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.glooboo.umessage.ui.main.FirstRunPagesHost
import com.glooboo.umessage.ui.spam.ChatMoveCounting
import com.google.common.truth.Truth.assertThat
import org.fossify.commons.extensions.baseConfig
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * H10: a start-up smoke test with the real [App] (the global Robolectric setting swaps it for a plain Application,
 * because App.onCreate starts a background thread; this class opts back in). Each test starts the app once more on
 * fresh data, or on data an older version left behind, waits for the start-up thread and checks that nothing crashed
 * and that the migrations ran. Fake numbers and words only.
 *
 * Robolectric creates the Application before @Before runs, so that first start-up is waited for and wiped, then each
 * test seeds its data and calls [App.onCreate] again, which is what the next launch of the app does.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = App::class)
class AppStartupSmokeTest {

    @get:Rule
    val helper = migrationTestHelper(SpamDatabase::class.java)

    private val app: App get() = ApplicationProvider.getApplicationContext<Application>() as App
    private val context: Context get() = app
    private val uncaught = mutableListOf<Throwable>()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        forgetSingletons()
        FakeMessageStore.install()
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> synchronized(uncaught) { uncaught += error } }
    }

    @After
    fun tearDown() {
        FirstRunPagesHost.reset()
        ChatMoveCounting.counter = ChatMoveCounting.UNKNOWN
        ShortcutHelper.stopWatchingSpam()
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        forgetSingletons()
    }

    /** Waits for the first start-up to finish, then closes and forgets what it left in process-wide singletons. */
    private fun forgetSingletons() {
        joinAppBackgroundThreads()
        SpamEngine.resetContactChecker()
        listOf("repository", "filter", "settingsStore", "forwardPlanner").forEach {
            SpamEngine::class.java.getDeclaredField(it).apply { isAccessible = true }.set(SpamEngine, null)
        }
        val instance = SpamDatabase::class.java.getDeclaredField("instance").apply { isAccessible = true }
        (instance.get(null) as? SpamDatabase)?.let { runCatching { it.close() } }
        instance.set(null, null)
        resetSharedMessagesDatabase()
    }

    private fun deleteSpamDatabaseFiles() {
        context.getDatabasePath(SpamDatabase.DATABASE_NAME).parentFile?.listFiles { _, name ->
            name.startsWith(SpamDatabase.DATABASE_NAME)
        }?.forEach { it.delete() }
    }

    /** What the next launch does: App.onCreate, then everything its background thread starts. */
    private fun startUp() {
        App.failedSteps.clear()
        app.onCreate()
        joinAppBackgroundThreads()
        assertThat(synchronized(uncaught) { uncaught.toList() }).isEmpty()
        // Each step's failure is caught so the next one runs (J13); it must still fail this test.
        assertThat(App.failedSteps).isEmpty()
    }

    private fun rules() = offMainThread { SpamEngine.repository(context).currentRules() }

    @Test
    fun theRealAppIsTheApplicationOfThisTest() {
        assertThat(ApplicationProvider.getApplicationContext<Application>()).isInstanceOf(App::class.java)
    }

    @Test
    fun freshInstall_startsWithoutACrashAndInstallsTheSpamEngine() {
        deleteSpamDatabaseFiles()

        startUp()

        assertThat(UMessage.filterFactory).isNotNull()
        assertThat(UMessage.repositoryFactory).isNotNull()
        assertThat(rules()).isEmpty()
        assertThat(offMainThread { SpamDatabase.getInstance(context).openHelper.readableDatabase.version })
            .isEqualTo(SpamDatabase.VERSION)
        assertThat(context.config.rawBlockedKeywords()).isEmpty()
    }

    @Test
    fun theFirstRunPagesAndTheSwitchCountAreInstalled() {
        FirstRunPagesHost.reset()
        ChatMoveCounting.counter = ChatMoveCounting.UNKNOWN

        app.installFirstRunPages()
        assertThat(FirstRunPagesHost.source.value).isInstanceOf(FirstRunProgressAdapter::class.java)
        assertThat(ChatMoveCounting.counter).isInstanceOf(SortDryRun::class.java)
    }

    /** E-26: the app-start MMS sweep asks the filter only after old blocked words and the old switch were moved. */
    @Test
    fun theMmsSweepRunsAfterTheBlockingMigration() {
        val names = app.startupSteps(startedAt = 0L).map { it.first }

        assertThat(names).containsNoDuplicates()
        assertThat(names.indexOf(App.MMS_SWEEP)).isGreaterThan(names.indexOf(App.BLOCKING_MIGRATION))
        assertThat(names.indexOf(App.BLOCKING_MIGRATION)).isAtLeast(0)
    }

    @Test
    fun startingTwiceInARow_isHarmless() {
        startUp()
        startUp()

        assertThat(rules()).isEmpty()
    }

    @Test
    fun upgradeFromAnOldSpamDatabase_migratesItAndKeepsTheRules() {
        deleteSpamDatabaseFiles()
        helper.createDatabase(SpamDatabase.DATABASE_NAME, 1).use { db ->
            db.execSQL(
                "INSERT INTO spam_rules (id, kind, type, pattern, subscriptionId, note, createdAt) " +
                    "VALUES (1, 'BLOCK', 'KEYWORD', 'lottery', NULL, '', 1000)"
            )
        }

        startUp()

        assertThat(rules().map { Triple(it.kind, it.type, it.pattern) })
            .containsExactly(Triple(ListKind.BLOCK, RuleType.KEYWORD, "lottery"))
        assertThat(offMainThread { SpamDatabase.getInstance(context).openHelper.readableDatabase.version })
            .isEqualTo(SpamDatabase.VERSION)
    }

    @Test
    fun upgradeFromOldBlockSettings_movesTheWordsIntoRulesAndClearsTheSwitch() {
        deleteSpamDatabaseFiles()
        context.config.blockedKeywords = setOf("legacyword", "  ")
        context.baseConfig.blockUnknownNumbers = true

        startUp()

        assertThat(rules().map { Triple(it.kind, it.type, it.pattern) })
            .containsExactly(Triple(ListKind.BLOCK, RuleType.KEYWORD, "legacyword"))
        assertThat(context.config.rawBlockedKeywords()).isEmpty()
        assertThat(context.baseConfig.blockUnknownNumbers).isFalse()
        assertThat(context.config.blockUnknownSwitchWasOn).isTrue()
    }
}
