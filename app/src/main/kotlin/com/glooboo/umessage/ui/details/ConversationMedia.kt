package com.glooboo.umessage.ui.details

import androidx.compose.runtime.Immutable
import com.glooboo.umessage.helpers.LinkExtractor
import com.glooboo.umessage.models.Attachment
import com.glooboo.umessage.models.Message
import com.glooboo.umessage.ui.main.SearchShortcuts

/** I56: one picture of a conversation and the message it came in. */
data class MediaImage(val message: Message, val attachment: Attachment)

/** I56: one web link of a conversation and the newest message that holds it. */
data class MediaLink(val url: String, val message: Message)

/** The pictures and links of one conversation, newest first. */
@Immutable
data class ConversationMedia(val images: List<MediaImage>, val links: List<MediaLink>) {
    val isEmpty: Boolean get() = images.isEmpty() && links.isEmpty()
}

/** The pictures of [messages] (newest first), at most [SearchShortcuts.LIMIT] messages' worth. */
fun mediaImages(messages: List<Message>): List<MediaImage> =
    SearchShortcuts.images(messages).flatMap { message ->
        message.attachment?.attachments.orEmpty()
            .filter { it.mimetype.startsWith("image/", ignoreCase = true) }
            .map { MediaImage(message, it) }
    }

/** The links of [messages] (newest first), each address once, at most [SearchShortcuts.LIMIT]. */
fun mediaLinks(messages: List<Message>, extractor: LinkExtractor = LinkExtractor()): List<MediaLink> {
    val seen = HashSet<String>()
    val links = ArrayList<MediaLink>()
    for (message in messages) {
        for (url in extractor.extract(message.body)) {
            if (seen.add(url)) links.add(MediaLink(url, message))
        }
        if (links.size >= SearchShortcuts.LIMIT) break
    }
    return links
}
