package com.glooboo.umessage.spam

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.spam.data.DefaultForwardRepository
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
 * Runs in the foss and the Google Play flavor: optional forwarding is present in both. The Google Play build used to
 * have none (this was GooglePlayNoForwardingTest); Google Play Developer Support confirmed in writing that it is
 * allowed for the default SMS app when it is optional, on the device and disclosed.
 */
@RunWith(AndroidJUnit4::class)
class ForwardingInEveryBuildTest {
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
    fun forwardingIsAvailable() {
        assertThat(ForwardingAvailability.isAvailable(context)).isTrue()
    }

    @Test
    fun theRepositoryIsTheRealOneAndShowsTheRule() {
        addRule()
        assertThat(SpamEngine.forwardRepository(context)).isInstanceOf(DefaultForwardRepository::class.java)
        assertThat(SpamEngine.forwardRepository(context).currentRules()).hasSize(1)
    }

    @Test
    fun aMatchingMessageIsForwarded() {
        addRule()
        val sent = mutableListOf<String>()
        ForwardDelivery.sender = ForwardSmsSender { _, text, _, _, _ -> sent += text }

        ForwardDelivery.onInboxMessage(context, ForwardCandidate("+15551234567", "Deposit of 500", 1))

        assertThat(sent).hasSize(1)
    }

    @Test
    fun theForwardingScreenIsInTheManifest() {
        val name = ComponentName(context, ForwardingActivity::class.java)
        val info = context.packageManager.getActivityInfo(name, PackageManager.MATCH_DISABLED_COMPONENTS)
        assertThat(info.name).isEqualTo(ForwardingActivity::class.java.name)
    }
}
