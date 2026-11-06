package com.glooboo.umessage.ui.details

import android.provider.Telephony
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.glooboo.umessage.R
import com.glooboo.umessage.models.Attachment
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.models.MessageAttachment
import com.glooboo.umessage.ui.theme.UMessageTheme
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class ConversationMediaTest {

    @get:Rule
    val compose = createComposeRule()

    private fun message(id: Long, body: String = "hi", types: List<String> = emptyList()) = Message(
        id = id,
        body = body,
        type = Telephony.Sms.MESSAGE_TYPE_INBOX,
        status = 0,
        participants = ArrayList(),
        date = id.toInt(),
        read = true,
        threadId = 1,
        isMMS = types.isNotEmpty(),
        attachment = types.takeIf { it.isNotEmpty() }?.let {
            MessageAttachment(
                id,
                "",
                ArrayList(
                    it.mapIndexed { i, type -> Attachment(null, id, "content://mms/part/$id$i", type, 0, 0, "f") },
                ),
            )
        },
        senderPhoneNumber = "+15550100",
        senderName = "",
        senderPhotoUri = "",
        subscriptionId = 0,
    )

    private fun text(res: Int) = ApplicationProvider.getApplicationContext<android.content.Context>().getString(res)

    @Test
    fun picturesAreListedPerAttachmentNewestFirstAndOnlyImages() {
        val found = mediaImages(
            listOf(
                message(3, types = listOf("image/jpeg", "image/png")),
                message(2, types = listOf("video/mp4")),
                message(1, "text only"),
            ),
        )
        assertThat(found.map { it.message.id }).containsExactly(3L, 3L)
    }

    @Test
    fun linksAreListedOnceKeepingTheNewestMessage() {
        val found = mediaLinks(
            listOf(
                message(3, "again https://one.test/a and www.two.test"),
                message(2, "no link"),
                message(1, "first https://one.test/a"),
            ),
        )
        assertThat(found.map { it.url }).containsExactly("https://one.test/a", "www.two.test").inOrder()
        assertThat(found.first().message.id).isEqualTo(3L)
    }

    @Test
    fun aChatWithoutPicturesAndLinksHasNoMedia() {
        assertThat(ConversationMedia(emptyList(), emptyList()).isEmpty).isTrue()
        assertThat(ConversationMedia(emptyList(), mediaLinks(listOf(message(1, "www.a.test")))).isEmpty).isFalse()
    }

    @Test
    fun theTabsShowTheirEmptyTexts() {
        compose.setContent {
            UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
                ConversationMediaScreen(
                    media = ConversationMedia(emptyList(), emptyList()),
                    onBack = {},
                    onOpenImage = {},
                    onOpenLink = {},
                    onShowInChat = {},
                )
            }
        }
        compose.onNodeWithText(text(R.string.i56_empty_pictures)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.i56_links)).performClick()
        compose.onNodeWithText(text(R.string.i56_empty_links)).assertIsDisplayed()
    }

    @Test
    fun aLinkRowShowsTheDomainAndTheAddress() {
        val media = ConversationMedia(emptyList(), mediaLinks(listOf(message(1, "go https://www.one.test/page"))))
        compose.setContent {
            UMessageTheme(darkTheme = false, dynamicColor = false, syncWindow = false) {
                ConversationMediaScreen(media, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("one.test").assertIsDisplayed()
        compose.onNodeWithText("https://www.one.test/page").assertIsDisplayed()
    }
}
