package com.glooboo.umessage.ui.conversations

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.helpers.SimNames
import com.glooboo.umessage.helpers.composerPrefs
import com.glooboo.umessage.models.Conversation
import com.glooboo.umessage.ui.spam.SimInfo
import com.glooboo.umessage.ui.spam.simLabel
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The SIM chip names the SIM the same way on Messages, Spam and Archived: nickname, else carrier, else "SIM n". */
@RunWith(AndroidJUnit4::class)
class SimLabelEverywhereTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val sims = listOf(SimInfo(11, 1, "Carrier One"), SimInfo(12, 2, ""))

    /** Shows [simLabel] for each of [ids] in one composition and returns the labels in the same order. */
    private fun spamLabels(vararg ids: Int, shown: List<SimInfo> = sims): List<String?> {
        var labels by mutableStateOf<List<String?>>(emptyList())
        compose.setContent { labels = ids.map { simLabel(shown, it) } }
        compose.waitForIdle()
        return labels
    }

    @Test
    fun theSpamLabelIsTheCarrierNameWhenThereIsNoNickname() {
        assertThat(spamLabels(11)).containsExactly("Carrier One")
        assertThat(SimBadge.displayName(context, sims[0])).isEqualTo("Carrier One")
    }

    @Test
    fun theSpamLabelIsTheSlotNameWhenTheSimHasNoCarrierName() {
        assertThat(spamLabels(12)).containsExactly("SIM 2")
    }

    @Test
    fun theSpamLabelIsTheNicknameTheUserGaveTheSim() {
        SimNames(context.composerPrefs).set(slot = 1, carrier = "Carrier One", nickname = "Work", number = "")
        assertThat(spamLabels(11)).containsExactly("Work")
        assertThat(SimBadge.displayName(context, sims[0])).isEqualTo("Work")
    }

    @Test
    fun noSpamLabelWithOneSim() {
        assertThat(spamLabels(11, shown = sims.take(1))).containsExactly(null as String?)
    }

    @Test
    fun noSpamLabelForAnUnknownSim() {
        assertThat(spamLabels(99)).containsExactly(null as String?)
    }

    @Test
    fun anArchivedRowCarriesTheSameChipAsTheMessagesRow() {
        val conversation = Conversation(
            threadId = 5, snippet = "hi", date = 0, read = true, title = "+15550001234", photoUri = "",
            isGroupConversation = false, phoneNumber = "+15550001234", unreadCount = 0,
        )
        val withChip = conversation.toFolderRow(context, emptyMap(), emptySet(), simSlot = 2, simName = "Work")
        assertThat(withChip.simSlot).isEqualTo(2)
        assertThat(withChip.simName).isEqualTo("Work")
        val withoutChip = conversation.toFolderRow(context, emptyMap(), emptySet())
        assertThat(withoutChip.simSlot).isNull()
        assertThat(withoutChip.simName).isEmpty()
    }
}
