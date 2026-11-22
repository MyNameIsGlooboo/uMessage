package com.glooboo.umessage.spam.engine

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.IncomingMessage
import com.glooboo.umessage.spam.UMessage
import com.glooboo.umessage.spam.data.DefaultSpamRepository
import com.glooboo.umessage.spam.data.PendingSmsEntity
import com.glooboo.umessage.spam.data.SpamDatabase
import com.glooboo.umessage.spam.data.SpamPlatform
import com.glooboo.umessage.spam.data.SpamSettingsStore
import com.glooboo.umessage.testing.SpamTestWiring
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R-1: a failed read of the rules or the members is never judged on half the facts. The snapshot throws and
 * [SpamEngine.judge] gives null, so the caller delivers the text to Messages (fail open), also when the read fails
 * after the fact was written. Every number is made up.
 */
@RunWith(AndroidJUnit4::class)
class SpamEngineJudgeTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var db: SpamDatabase
    private lateinit var repo: DefaultSpamRepository
    private var reads = 0
    private var failFromRead = Int.MAX_VALUE

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(app, SpamDatabase::class.java).allowMainThreadQueries().build()
        val platform = object : SpamPlatform {
            override fun notifyBlocked(message: BlockedMessage) = Unit
            override fun deliverRestored(pending: PendingSmsEntity): Long? = null
            override fun deliverRestoredMms(message: BlockedMessage): Long? = null
            override fun removeRestored(id: Long, isMms: Boolean) = false
            override fun deleteMedia(names: Collection<String>) = Unit
            override fun sweepMedia(keep: Set<String>) = Unit
        }
        repo = DefaultSpamRepository(platform, db, SpamSettingsStore(app), NumberNormalizer { "US" })
        val filter = DefaultSpamFilter(
            rules = {
                reads++
                check(reads < failFromRead) { "rules table unreadable" }
                repo.currentRules()
            },
            settings = repo::currentSettings,
            contacts = object : ContactChecker {
                override fun isContact(address: String) = false
            },
            normalizer = NumberNormalizer { "US" },
            groups = repo.groupMembership,
        )
        UMessage.filterFactory = { filter }
        UMessage.repositoryFactory = { repo }
    }

    @After fun tearDown() {
        SpamTestWiring.uninstall()
        db.close()
    }

    private val code = IncomingMessage(SENDER, "Your verification code is 482915", 1)

    @Test fun aWorkingReadJudges() {
        assertNotNull(SpamEngine.judge(app, code))
    }

    /** J3-1: a judgement (with its fact written) without any decision-log row; the default still writes one. */
    @Test fun withoutTheLog_itJudgesAndWritesTheFact_butNoRow() {
        UMessage.log(app).clear()

        assertNotNull(SpamEngine.judge(app, code, "MMS_CONTENT", writeLog = false))
        assertEquals(0, UMessage.log(app).entries().size)
        assertEquals(1, db.spamDao().getMembers().size)

        assertNotNull(SpamEngine.judge(app, code, "MMS_CONTENT"))
        assertEquals(1, UMessage.log(app).entries().size)
    }

    @Test fun withoutTheLog_aFailedRulesReadWritesNoErrorRow() {
        UMessage.log(app).clear()
        failFromRead = 1

        assertNull(SpamEngine.judge(app, code, writeLog = false))
        assertEquals(0, UMessage.log(app).entries().size)
    }

    @Test fun aFailedRulesReadGivesNoJudgement() {
        failFromRead = 1
        assertNull(SpamEngine.judge(app, code))
    }

    /** The fact is written, then the read for the place fails: no Spam verdict, the caller delivers to Messages. */
    @Test fun aFailureAfterTheFactWriteDoesNotEndInSpam() {
        failFromRead = 2
        assertNull(SpamEngine.judge(app, code))
        assertEquals(1, db.spamDao().getMembers().size)
    }

    private companion object {
        const val SENDER = "+15550001234"
    }
}
