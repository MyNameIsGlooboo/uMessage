package com.glooboo.umessage.spam

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.data.ForwardDelivery
import com.glooboo.umessage.spam.data.ForwardRuleEntity
import com.glooboo.umessage.spam.data.ForwardSmsSender
import com.glooboo.umessage.spam.data.SpamDatabase
import com.glooboo.umessage.spam.data.SystemForwardSender
import com.glooboo.umessage.spam.engine.ForwardCandidate
import com.glooboo.umessage.spam.engine.SpamEngine
import com.glooboo.umessage.testing.SpamTestWiring
import com.glooboo.umessage.ui.spam.ForwardingActivity
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Only compiled and run for the Google Play flavor (`:app:testGplayDebugUnitTest`): in that build forwarding does not
 * exist, even when a forward rule sits in the database (an old restore, a copied file).
 */
@RunWith(AndroidJUnit4::class)
class GooglePlayNoForwardingTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var spamDb: SpamDatabase

    @Before
    fun setUp() {
        spamDb = SpamTestWiring.freshSpamDatabase(context)
    }

    @After
    fun tearDown() {
        ForwardDelivery.sender = SystemForwardSender
        SpamTestWiring.clearSpamDatabase()
    }

    private fun addRule() = spamDb.forwardDao().insert(
        ForwardRuleEntity.from(
            ForwardRule(
                senderType = RuleType.NUMBER, senderPattern = "+15551234567", targets = listOf("+15550001111"),
            ),
        ),
    )

    @Test
    fun forwardingIsNotAvailable() {
        assertThat(ForwardingAvailability.isAvailable(context)).isFalse()
    }

    @Test
    fun theRepositoryShowsNoRulesEvenWhenARowIsInTheDatabase() {
        addRule()
        assertThat(SpamEngine.forwardRepository(context)).isSameInstanceAs(NoForwardRepository)
        assertThat(SpamEngine.forwardRepository(context).currentRules()).isEmpty()
    }

    @Test
    fun aMatchingMessageIsNotForwarded() {
        addRule()
        val sent = mutableListOf<String>()
        ForwardDelivery.sender = ForwardSmsSender { _, text, _, _, _ -> sent += text }

        ForwardDelivery.onInboxMessage(context, ForwardCandidate("+15551234567", "Deposit of 500", 1))

        assertThat(sent).isEmpty()
        assertThat(spamDb.forwardLogDao().touchedSince(0L)).isEmpty()
    }

    @Test
    fun theSystemSenderRefusesToSend() {
        val result = runCatching { SystemForwardSender.send(context, "[from x] hi", "+15550001111", null, null) }
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun theForwardingScreenIsNotInTheManifest() {
        val name = ComponentName(context, ForwardingActivity::class.java)
        val result = runCatching {
            context.packageManager.getActivityInfo(name, PackageManager.MATCH_DISABLED_COMPONENTS)
        }
        assertThat(result.exceptionOrNull()).isInstanceOf(PackageManager.NameNotFoundException::class.java)
    }
}
