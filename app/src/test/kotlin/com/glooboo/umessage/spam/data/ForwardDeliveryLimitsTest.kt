package com.glooboo.umessage.spam.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.NavPrefs
import com.glooboo.umessage.spam.ForwardRule
import com.glooboo.umessage.spam.RuleType
import com.glooboo.umessage.spam.engine.ForwardCandidate
import com.glooboo.umessage.spam.engine.ForwardRateLimit
import com.glooboo.umessage.testing.SpamTestWiring
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The two cost guards of [ForwardDelivery.onInboxMessage]: "pause all forwarding" and the hourly cap of a rule. */
@RunWith(AndroidJUnit4::class)
class ForwardDeliveryLimitsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val sent = mutableListOf<String>()

    @Before
    fun setUp() {
        val db = SpamTestWiring.freshSpamDatabase(context)
        db.forwardDao().insert(
            ForwardRuleEntity.from(
                ForwardRule(
                    senderType = RuleType.NUMBER, senderPattern = "+15551234567", targets = listOf("+15550001111"),
                ),
            ),
        )
        ForwardDelivery.sender = ForwardSmsSender { _, text, _, _, _ -> sent += text }
    }

    @After
    fun tearDown() {
        NavPrefs.get(context).forwardPaused = false
        ForwardDelivery.sender = SystemForwardSender
        SpamTestWiring.clearSpamDatabase()
    }

    private fun receive(n: Int) =
        ForwardDelivery.onInboxMessage(context, ForwardCandidate("+15551234567", "Deposit of $n", 1))

    @Test
    fun nothingIsSentWhileForwardingIsPaused() {
        NavPrefs.get(context).forwardPaused = true

        receive(1)

        assertThat(sent).isEmpty()
    }

    @Test
    fun forwardingResumesWhenThePauseIsLifted() {
        NavPrefs.get(context).forwardPaused = true
        receive(1)
        NavPrefs.get(context).forwardPaused = false

        receive(2)

        assertThat(sent).hasSize(1)
    }

    @Test
    fun aRuleStopsAtTheHourlyCap() {
        repeat(ForwardRateLimit.MAX_PER_RULE_PER_HOUR + 5) { receive(it) }

        assertThat(sent).hasSize(ForwardRateLimit.MAX_PER_RULE_PER_HOUR)
    }
}
