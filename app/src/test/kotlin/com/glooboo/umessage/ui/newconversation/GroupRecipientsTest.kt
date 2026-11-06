package com.glooboo.umessage.ui.newconversation

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.extensions.joinGroupNames
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** MC-13: picking the people of a new group, and how a group is titled. */
@RunWith(AndroidJUnit4::class)
class GroupRecipientsTest {

    private val sara = GroupRecipient("Sara", "+44 712 111 2222")
    private val ali = GroupRecipient("Ali", "0712 333 4444")

    @Test
    fun aSecondTapOnTheSameNumberRemovesThePerson() {
        val one = toggleRecipient(emptyList(), sara)
        val two = toggleRecipient(one, ali)
        assertThat(two).containsExactly(sara, ali).inOrder()
        // The same number written differently is the same person.
        assertThat(toggleRecipient(two, GroupRecipient("Sara", "+447121112222"))).containsExactly(ali)
    }

    @Test
    fun typingAPickedNumberAgainKeepsOneChip() {
        val picked = listOf(sara)
        assertThat(addRecipient(picked, GroupRecipient("+447121112222", "+447121112222"))).containsExactly(sara)
        assertThat(isPicked(picked, "+44-712-111-2222")).isTrue()
        assertThat(isPicked(picked, "07121112222")).isFalse()
    }

    @Test
    fun theSaverKeepsNamesAndNumbers() {
        val saved = with(GroupRecipientsSaver) { androidx.compose.runtime.saveable.SaverScope { true }.save(listOf(sara, ali)) }
        assertThat(GroupRecipientsSaver.restore(saved!!)).containsExactly(sara, ali).inOrder()
    }

    @Test
    fun bigGroupsShowThreeNamesAndHowManyMore() {
        assertThat(joinGroupNames(listOf("Sara", "Ali"))).isEqualTo("Sara, Ali")
        assertThat(joinGroupNames(listOf("Sara", "Ali", "Reza"))).isEqualTo("Sara, Ali, Reza")
        assertThat(joinGroupNames(listOf("Sara", "Ali", "Reza", "Mina", "Omid"))).isEqualTo("Sara, Ali, Reza +2")
    }
}
