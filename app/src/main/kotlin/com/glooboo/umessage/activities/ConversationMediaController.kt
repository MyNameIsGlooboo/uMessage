package com.glooboo.umessage.activities

import android.app.Activity
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import com.glooboo.umessage.R
import com.glooboo.umessage.extensions.launchViewIntent
import com.glooboo.umessage.helpers.threadPrefs
import com.glooboo.umessage.ui.details.ConversationMedia
import com.glooboo.umessage.ui.details.ConversationMediaScreen
import com.glooboo.umessage.ui.details.MediaActions
import com.glooboo.umessage.ui.details.MediaImage
import com.glooboo.umessage.ui.thread.LinkSheet
import com.glooboo.umessage.ui.thread.linkCopyText
import com.glooboo.umessage.ui.thread.shareLink
import org.fossify.commons.extensions.copyToClipboard

/**
 * I56: what the "Media and links" section of Conversation details opens: the full list, a picture in the viewer, and
 * a link. A link never goes straight to a browser: it opens the same sheet as in a chat, with the whole address and,
 * for a sender who is not a saved contact, the line asking to check it first. [trusted] says the sender is saved.
 */
internal class ConversationMediaController(
    private val activity: Activity,
    private val threadId: () -> Long,
    private val trusted: () -> Boolean,
) {
    /** The full list is showing instead of the details. */
    var open by mutableStateOf(false)
        private set

    private var pendingLink by mutableStateOf<String?>(null)

    val actions = MediaActions(
        onSeeAll = { open = true },
        onOpenImage = ::openImage,
        onOpenLink = { pendingLink = it },
    )

    /** The link sheet, while a link is picked. */
    @Composable
    fun LinkSheetHost() {
        pendingLink?.let { uri ->
            LinkSheet(
                uri = uri,
                trusted = trusted(),
                onOpen = {
                    pendingLink = null
                    openLink(uri)
                },
                onCopy = {
                    pendingLink = null
                    activity.copyToClipboard(linkCopyText(uri))
                },
                onShare = {
                    pendingLink = null
                    shareLink(activity, uri)
                },
                onDismiss = { pendingLink = null },
                lockRisky = activity.threadPrefs.riskyLinksLocked,
            )
        }
    }

    @Composable
    fun Page(media: ConversationMedia) {
        BackHandler { open = false }
        ConversationMediaScreen(
            media = media,
            onBack = { open = false },
            onOpenImage = ::openImage,
            onOpenLink = { pendingLink = it },
            onShowInChat = { message ->
                activity.openThreadAt(threadId(), message)
                activity.finish()
            },
        )
    }

    private fun openImage(image: MediaImage) {
        val attachment = image.attachment
        activity.launchViewIntent(attachment.getUri(), attachment.mimetype, attachment.filename)
    }

    private fun openLink(uri: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, uri.toUri()))
        } catch (_: Exception) {
            Toast.makeText(activity, R.string.thread_no_app, Toast.LENGTH_SHORT).show()
        }
    }
}
