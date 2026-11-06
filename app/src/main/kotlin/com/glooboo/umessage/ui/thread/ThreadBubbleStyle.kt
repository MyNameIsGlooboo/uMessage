package com.glooboo.umessage.ui.thread

import android.text.SpannableString
import android.text.style.URLSpan
import android.text.util.Linkify
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/* The look of a chat bubble that needs no composition: its corners, colours and the text with links and matches. */

private val BigCorner = 20.dp
private val SmallCorner = 4.dp

/** Corners of a chat bubble: the side facing the previous or next bubble of the same run is squared off. */
fun bubbleShape(sent: Boolean, group: BubbleGroup): Shape {
    val continuesAbove = group == BubbleGroup.Middle || group == BubbleGroup.Last
    val continuesBelow = group == BubbleGroup.First || group == BubbleGroup.Middle
    val inner = SmallCorner
    val top = if (continuesAbove) inner else BigCorner
    val bottom = if (continuesBelow) inner else BigCorner
    return if (sent) {
        RoundedCornerShape(topStart = BigCorner, topEnd = top, bottomEnd = bottom, bottomStart = BigCorner)
    } else {
        RoundedCornerShape(topStart = top, topEnd = BigCorner, bottomEnd = BigCorner, bottomStart = bottom)
    }
}

/** Plain text with web and e-mail addresses turned into tappable links, as the old bubbles did with autoLink. */
internal fun linkify(text: String, color: Color): AnnotatedString {
    if (text.isEmpty()) return AnnotatedString("")
    val spannable = SpannableString(text)
    Linkify.addLinks(spannable, Linkify.WEB_URLS or Linkify.EMAIL_ADDRESSES)
    val spans = spannable.getSpans(0, spannable.length, URLSpan::class.java)
    if (spans.isEmpty()) return AnnotatedString(text)
    val style = TextLinkStyles(
        SpanStyle(color = color, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Medium),
    )
    return buildAnnotatedString {
        // TH-04: each link sits in a left-to-right isolate, so a URL inside right-to-left text keeps its own order
        // and lines up with the surrounding text instead of drifting to the other edge.
        var last = 0
        spans.sortedBy { spannable.getSpanStart(it) }.forEach { span ->
            val start = spannable.getSpanStart(span)
            val end = spannable.getSpanEnd(span)
            if (start < last) return@forEach
            append(text.substring(last, start))
            append(LRI)
            val from = length
            append(keepSchemeTogether(text.substring(start, end)))
            addLink(LinkAnnotation.Url(span.url, style), from, length)
            append(PDI)
            last = end
        }
        append(text.substring(last))
    }
}

private const val LRI = "\u2066"
private const val PDI = "\u2069"
private const val WORD_JOINER = "\u2060"

/**
 * TH-04: a word joiner after "://", so a line never ends at "https://" with the address on the next line. Only the
 * shown text changes; the link opens [URLSpan.getURL] as it was.
 */
internal fun keepSchemeTogether(link: String): String = link.replaceFirst("://", "://$WORD_JOINER")

/** [text] with every match of [query] (Search in conversation) drawn in [style]. */
internal fun highlightMatches(text: AnnotatedString, query: String, style: SpanStyle): AnnotatedString {
    val ranges = searchRanges(text.text, query)
    if (ranges.isEmpty()) return text
    return AnnotatedString.Builder(text).apply {
        ranges.forEach { addStyle(style, it.first, it.last + 1) }
    }.toAnnotatedString()
}

/** Share of primary mixed into the page colour for a dark sent bubble, and the darkest container used as it is. */
private const val DARK_SENT_PRIMARY_SHARE = 0.3f
private const val DARK_CONTAINER_MAX_LUMINANCE = 0.12f

/** N6: more primary is mixed in, a step at a time up to the most, until sent and received bubbles differ enough. */
private const val DARK_SENT_SHARE_STEP = 0.05f
private const val DARK_SENT_MAX_STEPS = 6

/** N6: the least contrast between the sent and received bubble colours that reads as two tones at a glance. */
private const val MIN_BUBBLE_TONE_CONTRAST = 1.5f

/** The text on a sent bubble keeps at least normal-text contrast while the bubble gets lighter. */
private const val MIN_TEXT_CONTRAST = 4.5f

/** The flare term of the WCAG contrast formula. */
private const val CONTRAST_FLARE = 0.05f

/** WCAG contrast ratio of two colours, 1 (same luminance) to 21. */
internal fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance() + CONTRAST_FLARE
    val lb = b.luminance() + CONTRAST_FLARE
    return maxOf(la, lb) / minOf(la, lb)
}

/**
 * TH-31: a sent bubble in a dark theme is a dark tone of the accent with light text, never a bright pastel slab.
 * The theme's primaryContainer is used when it is dark enough, else primary mixed into the page colour.
 * N6: either way it must stand apart from the [received] bubble colour; when it does not, more primary is mixed in
 * (as long as [onSurface] text stays readable on it). Returns the bubble colour and its text colour.
 */
internal fun darkSentBubbleColors(
    primaryContainer: Color,
    onPrimaryContainer: Color,
    primary: Color,
    surface: Color,
    onSurface: Color,
    received: Color,
): Pair<Color, Color> {
    if (primaryContainer.luminance() <= DARK_CONTAINER_MAX_LUMINANCE &&
        contrastRatio(primaryContainer, received) >= MIN_BUBBLE_TONE_CONTRAST
    ) {
        return primaryContainer to onPrimaryContainer
    }
    // The mixes from the usual share up to the most, kept while the text stays readable; the first that stands apart
    // from the received bubble wins, else the strongest readable one.
    val mixes = (0..DARK_SENT_MAX_STEPS)
        .map { step -> lerp(surface, primary, DARK_SENT_PRIMARY_SHARE + step * DARK_SENT_SHARE_STEP) }
        .filterIndexed { step, mix -> step == 0 || contrastRatio(onSurface, mix) >= MIN_TEXT_CONTRAST }
    val bubble = mixes.firstOrNull { contrastRatio(it, received) >= MIN_BUBBLE_TONE_CONTRAST } ?: mixes.last()
    return bubble to onSurface
}

/** TH-05: links in a received bubble take the accent colour; on a sent or selected bubble they keep the text colour. */
internal fun bubbleLinkColor(sent: Boolean, selected: Boolean, content: Color, accent: Color): Color =
    if (sent || selected) content else accent

/** Share of the text colour mixed into the received bubble in a dark theme (U58). */
private const val DARK_RECEIVED_LIFT = 0.08f

/** U58: the received bubble colour; in a dark theme [highest] lifted one tonal step towards [onSurface]. */
internal fun receivedBubbleColor(highest: Color, onSurface: Color, dark: Boolean): Color =
    if (dark) lerp(highest, onSurface, DARK_RECEIVED_LIFT) else highest
