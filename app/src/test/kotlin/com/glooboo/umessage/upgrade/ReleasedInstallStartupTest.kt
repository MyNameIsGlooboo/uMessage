package com.glooboo.umessage.upgrade

import android.app.Application
import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.App
import com.glooboo.umessage.databases.MessagesDatabase
import com.glooboo.umessage.helpers.ShortcutHelper
import com.glooboo.umessage.models.Draft
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.SpamSorting
import com.glooboo.umessage.spam.data.SpamDatabase
import com.glooboo.umessage.spam.data.SpamSettingsStore
import com.glooboo.umessage.spam.engine.SpamEngine
import com.glooboo.umessage.testing.FakeMessageStore
import com.glooboo.umessage.testing.joinAppBackgroundThreads
import com.glooboo.umessage.testing.migrationTestHelper
import com.glooboo.umessage.testing.offMainThread
import com.glooboo.umessage.testing.resetSharedMessagesDatabase
import com.glooboo.umessage.testing.resetSortingState
import com.glooboo.umessage.ui.main.FirstRunPagesHost
import com.glooboo.umessage.ui.onboarding.OnboardingState
import com.glooboo.umessage.ui.spam.ChatMoveCounting
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * J13 (upgrade safety): the first start of this version on the data a released version left behind, with the real
 * [App] (like AppStartupSmokeTest). The user finished the welcome, so the start must be told an update (not a fresh
 * install, which would show the welcome again), migrate both databases, keep the user's choices and lose no rule,
 * held message or draft. Robolectric is never the default SMS app, so held messages wait in the old table (section 9).
 * Made-up data, 555 numbers only.
 *
 * Robolectric starts the Application before @Before; that start is waited for and its files are wiped, then each test
 * seeds the old data and calls [App.onCreate] again, which is what the first launch after the update does.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = App::class)
class ReleasedInstallStartupTest {

    @get:Rule
    val spamHelper = migrationTestHelper(SpamDatabase::class.java)

    @get:Rule
    val messagesHelper = migrationTestHelper(MessagesDatabase::class.java)

    private val app: App get() = ApplicationProvider.getApplicationContext<Application>() as App
    private val context: Context get() = app
    private val uncaught = mutableListOf<Throwable>()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun setUp() {
        forgetSingletons()
        FakeMessageStore.install()
        // What Robolectric's own first start wrote; an updated phone has none of it.
        listOf(FIRST_RUN_PREFS, "umessage_install", SPAM_PREFS, "spamless_onboarding").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        deleteDatabaseFiles()
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
        resetSortingState()
    }

    private fun deleteDatabaseFiles() {
        val names = listOf(SpamDatabase.DATABASE_NAME, MessagesDatabase.DATABASE_NAME)
        context.getDatabasePath(SpamDatabase.DATABASE_NAME).parentFile
            ?.listFiles { _, name -> names.any { name.startsWith(it) } }
            ?.forEach { it.delete() }
    }

    /** The first launch after the update: App.onCreate, its start-up thread, then the first run's check. */
    private fun startUp() {
        App.failedSteps.clear()
        app.onCreate()
        joinAppBackgroundThreads()
        val idle = CountDownLatch(1)
        SpamSorting.execute(Runnable { idle.countDown() })
        assertThat(idle.await(SORT_WAIT_S, TimeUnit.SECONDS)).isTrue()
        joinAppBackgroundThreads()
        assertThat(synchronized(uncaught) { uncaught.toList() }).isEmpty()
        // No start-up step (migrations, sweeps, the held-message move) may fail on the old data.
        assertThat(App.failedSteps).isEmpty()
    }

    private fun SupportSQLiteDatabase.rule(id: Long, kind: String, type: String, pattern: String, sim: Int?) =
        execSQL(
            "INSERT INTO spam_rules (id, kind, type, pattern, subscriptionId, note, createdAt) " +
                "VALUES (?, ?, ?, ?, ?, '', ?)",
            arrayOf<Any?>(id, kind, type, pattern, sim, id * 100),
        )

    private fun seedSpamDatabase(version: Int) {
        spamHelper.createDatabase(SpamDatabase.DATABASE_NAME, version).use { db ->
            db.rule(1, "BLOCK", "NUMBER", SPAMMER, 1)
            db.rule(2, "BLOCK", "NUMBER", SPAMMER, 2)
            db.rule(3, "ALLOW", "SENDER_ID", "MYBANK", null)
            db.execSQL(
                "INSERT INTO blocked_messages (id, address, body, subscriptionId, isMms, receivedAt, reason, ruleId) " +
                    "VALUES (1, '$SPAMMER', 'Win a prize', 1, 0, 1000, 'BLOCK_RULE', 2), " +
                    "(2, '$STRANGER', 'Hello there', 1, 0, 2000, 'UNKNOWN_SENDER', NULL)"
            )
        }
    }

    private fun seedMessagesDatabase(version: Int) {
        messagesHelper.createDatabase(MessagesDatabase.DATABASE_NAME, version).use { db ->
            db.execSQL("INSERT INTO drafts (thread_id, body, date) VALUES (1, 'See you', 5)")
        }
    }

    /** [installMarker]: InstallDefaults' marker, which every start of 0.4.3 and later left behind. */
    private fun seedPrefs(mode: String, installMarker: Boolean) {
        context.getSharedPreferences("spamless_onboarding", Context.MODE_PRIVATE).edit()
            .putBoolean("completed", true).commit()
        if (installMarker) {
            context.getSharedPreferences("umessage_install", Context.MODE_PRIVATE).edit()
                .putBoolean("defaults_applied", true).commit()
        }
        context.getSharedPreferences(SPAM_PREFS, Context.MODE_PRIVATE).edit()
            .putString("mode", mode)
            .putInt("purge_after_days", 30)
            .putBoolean("notify_on_block", true)
            .putString("unknown_inbox_notify", "SILENT")
            .commit()
    }

    private fun checkUpdated(filterOn: Boolean) {
        val firstRun = context.getSharedPreferences(FIRST_RUN_PREFS, Context.MODE_PRIVATE)
        assertThat(firstRun.getString("install_kind", null)).isEqualTo("UPDATE")
        // The update's pass began ("What changed" counts from it). A later pass may follow it, never a fresh one.
        assertThat(firstRun.getLong("what_changed_since", 0L)).isGreaterThan(0L)
        assertThat(firstRun.getString("trigger", null)).isNotEqualTo("FRESH_INSTALL")
        assertThat(OnboardingState.isCompleted(context)).isTrue()

        val settings = SpamSettingsStore(context).get()
        assertThat(settings.filterOn).isEqualTo(filterOn)
        assertThat(settings.purgeAfterDays).isEqualTo(0)
        assertThat(settings.notifyOnBlock).isTrue()

        offMainThread {
            assertThat(SpamDatabase.getInstance(context).openHelper.readableDatabase.version)
                .isEqualTo(SpamDatabase.VERSION)
            val rules = SpamEngine.repository(context).currentRules()
            assertThat(rules.map { Triple(it.kind, it.type, it.pattern) }).containsExactly(
                Triple(ListKind.BLOCK, RuleType.NUMBER, SPAMMER),
                Triple(ListKind.ALLOW, RuleType.SENDER_ID, "MYBANK"),
            )
            assertThat(rules.map { it.subscriptionId }.toSet()).containsExactly(null)
            assertThat(SpamDatabase.getInstance(context).spamDao().getAllBlocked().map { it.body })
                .containsExactly("Win a prize", "Hello there")

            val messages = MessagesDatabase.getInstance(context)
            assertThat(messages.openHelper.readableDatabase.version).isEqualTo(MessagesDatabase.VERSION)
            assertThat(messages.DraftsDao().getAll()).containsExactly(Draft(1, "See you", 5))
        }
    }

    /** 0.4.7 to 0.4.14: spamless.db 8, conversations.db 15. An Off user stays off. */
    @Test
    fun anOffUserOf0414StartsAsAnUpdateAndKeepsEverything() {
        seedSpamDatabase(version = 8)
        seedMessagesDatabase(version = 15)
        seedPrefs(mode = "OFF", installMarker = true)

        startUp()

        checkUpdated(filterOn = false)
    }

    /** 0.3.0: spamless.db 5, conversations.db 13, the oldest release. A Strict user gets the filter on. */
    @Test
    fun aStrictUserOf030StartsAsAnUpdateAndKeepsEverything() {
        seedSpamDatabase(version = 5)
        seedMessagesDatabase(version = 13)
        seedPrefs(mode = "STRICT", installMarker = false)

        startUp()

        checkUpdated(filterOn = true)
    }

    private companion object {
        const val FIRST_RUN_PREFS = "umessage_first_run"
        const val SPAM_PREFS = "spamless_spam_settings"
        const val SPAMMER = "+15550009876"
        const val STRANGER = "+15550001299"
        const val SORT_WAIT_S = 30L
    }
}
