package com.glooboo.umessage.spam.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.DecisionReason
import com.glooboo.umessage.spam.ListKind
import com.glooboo.umessage.spam.RuleType
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Smoke test for an in-memory SpamDatabase: the pattern later waves use for DAO and repository tests. */
@RunWith(AndroidJUnit4::class)
class SpamDaoRoundTripTest {

    private lateinit var db: SpamDatabase
    private lateinit var dao: SpamDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, SpamDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.spamDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun ruleRoundTrip() {
        val id = dao.insertRule(
            SpamRuleEntity(
                kind = ListKind.BLOCK, type = RuleType.KEYWORD, pattern = "lottery",
                subscriptionId = null, note = "", createdAt = 1000,
            )
        )

        val found = dao.findRule(ListKind.BLOCK, RuleType.KEYWORD, "lottery", null)
        assertThat(found?.id).isEqualTo(id)

        dao.deleteRule(id)
        assertThat(dao.getRules()).isEmpty()
    }

    @Test
    fun blockedMessagesFlowEmitsInsertedRow() = runTest {
        dao.insertBlocked(
            BlockedMessageEntity(
                address = "5550100", body = "win", subscriptionId = 1, isMms = false,
                receivedAt = 2000, reason = DecisionReason.BLOCK_RULE, ruleId = null,
            )
        )

        val blocked = dao.observeBlocked().first()
        assertThat(blocked.map { it.body }).containsExactly("win")
    }
}
