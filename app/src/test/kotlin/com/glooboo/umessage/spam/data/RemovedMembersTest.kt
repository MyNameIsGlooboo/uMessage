package com.glooboo.umessage.spam.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.AllowGroup
import com.glooboo.umessage.spam.BlockedMessage
import com.glooboo.umessage.spam.MemberSource
import com.glooboo.umessage.spam.engine.NumberNormalizer
import com.glooboo.umessage.ui.spam.RuleNotes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * B-9: a sender the user took out of a group with "Remove" is never put back by the history check, in any written
 * form of the number and across app starts; a new code or a reply still puts them back. Numbers are made up.
 */
@RunWith(AndroidJUnit4::class)
class RemovedMembersTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var db: SpamDatabase
    private lateinit var repo: DefaultSpamRepository

    @Before fun setUp() {
        RemovedMembers.reset()
        RemovedMembers.install(app.getSharedPreferences(RemovedMembers.PREFS, Context.MODE_PRIVATE).also {
            it.edit().clear().commit()
        })
        db = Room.inMemoryDatabaseBuilder(app, SpamDatabase::class.java).allowMainThreadQueries().build()
        val platform = object : SpamPlatform {
            override fun notifyBlocked(message: BlockedMessage) = Unit
            override fun deliverRestored(pending: PendingSmsEntity): Long? = null
            override fun deliverRestoredMms(message: BlockedMessage): Long? = null
            override fun removeRestored(id: Long, isMms: Boolean) = false
            override fun deleteMedia(names: Collection<String>) = Unit
            override fun sweepMedia(keep: Set<String>) = Unit
        }
        repo = DefaultSpamRepository(
            platform, db, SpamSettingsStore(app), NumberNormalizer { "US" },
            blockedFromMessageNote = { RuleNotes.BLOCKED_FROM_MESSAGE },
            blockedFromKeywordNote = { RuleNotes.BLOCKED_FROM_KEYWORD },
            allowedFromKeywordNote = { RuleNotes.ALLOWED_FROM_KEYWORD },
        )
    }

    @After fun tearDown() {
        db.close()
        RemovedMembers.reset()
    }

    private suspend fun members() = repo.groupMembers.first()

    @Test fun aRemovedSenderIsNotPutBackByTheHistoryCheck() = runTest {
        assertTrue(repo.joinGroup(AllowGroup.WROTE, SENDER, MemberSource.LIVE))
        repo.removeGroupMembers(members())

        assertEquals(0, repo.joinGroups(AllowGroup.WROTE, listOf("(555) 000-1234"), MemberSource.HISTORY))
        assertFalse(repo.joinGroup(AllowGroup.WROTE, SENDER, MemberSource.HISTORY))
        assertTrue(members().isEmpty())

        // Another group, and another sender, are not affected.
        assertEquals(1, repo.joinGroups(AllowGroup.CODE, listOf(SENDER), MemberSource.HISTORY))
        assertEquals(1, repo.joinGroups(AllowGroup.WROTE, listOf(OTHER), MemberSource.HISTORY))
    }

    @Test fun aReplyOrACodePutsTheSenderBackAndClearsTheMark() = runTest {
        repo.joinGroup(AllowGroup.WROTE, SENDER, MemberSource.LIVE)
        repo.removeGroupMembers(members())

        assertTrue(repo.joinGroup(AllowGroup.WROTE, SENDER, MemberSource.LIVE))
        assertFalse(RemovedMembers.contains(AllowGroup.WROTE, listOf("+15550001234")))
    }

    @Test fun undoOfRemoveClearsTheMark() = runTest {
        repo.joinGroup(AllowGroup.CODE, SENDER, MemberSource.LIVE)
        val removed = repo.removeGroupMembers(members())
        repo.restoreGroupMembers(removed)

        assertEquals(0, repo.joinGroups(AllowGroup.CODE, listOf(SENDER), MemberSource.HISTORY))
        assertEquals(1, members().size)
        assertFalse(RemovedMembers.contains(AllowGroup.CODE, listOf("+15550001234")))
    }

    @Test fun theMarkSurvivesAnAppStart() = runTest {
        repo.joinGroup(AllowGroup.WROTE, SENDER, MemberSource.LIVE)
        repo.removeGroupMembers(members())

        // A new process: the object forgets its memory, the preferences stay.
        RemovedMembers.reset()
        RemovedMembers.install(app.getSharedPreferences(RemovedMembers.PREFS, Context.MODE_PRIVATE))

        assertEquals(0, repo.joinGroups(AllowGroup.WROTE, listOf(SENDER), MemberSource.HISTORY))
    }

    private companion object {
        const val SENDER = "+15550001234"
        const val OTHER = "+15550001235"
    }
}
