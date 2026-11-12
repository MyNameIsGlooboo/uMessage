package com.glooboo.umessage.ui.conversations

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.glooboo.umessage.ui.icons.Symbols
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private val AvatarSize = 48.dp

/** The person silhouette of a number-only sender fills this share of the circle. */
private const val SILHOUETTE_FRACTION = 0.55f

/** Below this background luminance the theme is dark and the avatar tones switch to their dark pairs. */
private const val DARK_BACKGROUND_LUMINANCE = 0.5f

/**
 * CL-05: eight muted tones, each a container and a text colour for light and for dark themes. Fixed, so a contact's
 * colour never changes with the wallpaper or the theme preset, and calm, so no bright pink or saturated blue circle
 * pulls the eye in a list of chats.
 */
private enum class AvatarTone(val light: Long, val onLight: Long, val dark: Long, val onDark: Long) {
    Blue(0xFFD7E3F4, 0xFF1F3A5C, 0xFF2E4562, 0xFFD7E3F4),
    Teal(0xFFCFE8E3, 0xFF16433C, 0xFF24504A, 0xFFCFE8E3),
    Green(0xFFDDEAD3, 0xFF2B4421, 0xFF34502A, 0xFFDDEAD3),
    Sand(0xFFF0E4CD, 0xFF4A3815, 0xFF574628, 0xFFF0E4CD),
    Rose(0xFFF1DDE0, 0xFF5A2B32, 0xFF633A41, 0xFFF1DDE0),
    Lavender(0xFFE4DEF1, 0xFF3A3057, 0xFF463D63, 0xFFE4DEF1),
    Slate(0xFFDDE3EA, 0xFF2E3A47, 0xFF3A4654, 0xFFDDE3EA),
    Peach(0xFFF4DFD2, 0xFF5A3521, 0xFF654330, 0xFFF4DFD2),
    ;

    fun colors(dark: Boolean): Pair<Color, Color> =
        if (dark) Color(this.dark) to Color(onDark) else Color(light) to Color(onLight)
}

/**
 * Container and content colour of an avatar. CL-05: a calm tone picked by a stable hash of the title, so the same
 * contact always has the same colour. U33: a bare number gets one too (from its digits), so rows of unsaved senders
 * tell apart. CL-39: the group circle is a primary tone, not a dim tertiary.
 */
private fun avatarColors(colors: ColorScheme, title: String, isGroup: Boolean, selected: Boolean) =
    when {
        selected -> colors.primary to colors.onPrimary
        isGroup -> colors.primaryContainer to colors.onPrimaryContainer
        else -> AvatarTone.entries[avatarToneIndex(title, AvatarTone.entries.size)]
            .colors(dark = colors.background.luminance() < DARK_BACKGROUND_LUMINANCE)
    }

/**
 * Round avatar: contact photo, else group icon, else initials on a tone picked from the title, else (a bare number) a
 * person silhouette. [selected] shows a check.
 */
@Composable
fun ContactAvatar(
    title: String,
    photoUri: String,
    modifier: Modifier = Modifier,
    isGroup: Boolean = false,
    selected: Boolean = false,
    size: Dp = AvatarSize,
) {
    val colors = MaterialTheme.colorScheme
    val photo = rememberAvatarBitmap(photoUri)
    val initials = initialsOf(title)
    val (container, content) = avatarColors(colors, title, isGroup, selected)
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(container)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        when {
            selected -> Icon(Symbols.Check, contentDescription = null, tint = colors.onPrimary)
            photo != null -> Image(
                bitmap = photo,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            isGroup -> Icon(Symbols.Group, contentDescription = null, tint = content)
            // Number-only senders get the person silhouette, on a tone of their own (U33).
            initials.isEmpty() -> Icon(
                Symbols.PersonFilled,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(size * SILHOUETTE_FRACTION),
            )
            else -> Text(text = initials, style = MaterialTheme.typography.titleMedium, color = content)
        }
    }
}

/** Up to two letters: first letter of the first two words. Empty for numbers and symbols (person icon instead). */
internal fun initialsOf(title: String): String =
    title.trim().split(' ', '\t').filter { it.isNotEmpty() }
        .mapNotNull { word -> word.firstOrNull { it.isLetter() }?.uppercaseChar() }
        .take(2)
        .joinToString("")

private object AvatarCache {
    val bitmaps = object : LruCache<String, ImageBitmap>(AVATAR_CACHE_SIZE) {}
    const val AVATAR_CACHE_SIZE = 200

    /** Photos that failed to load, by when: not retried for a minute, so a broken one is not read again per row. */
    private val failures = object : LruCache<String, Long>(AVATAR_CACHE_SIZE) {}
    private const val RETRY_AFTER_MS = 60_000L

    fun failedRecently(uri: String): Boolean {
        val at = failures.get(uri) ?: return false
        return SystemClock.elapsedRealtime() - at < RETRY_AFTER_MS
    }

    fun markFailed(uri: String) {
        failures.put(uri, SystemClock.elapsedRealtime())
    }
}

@Composable
private fun rememberAvatarBitmap(uri: String): ImageBitmap? {
    if (uri.isEmpty()) return null
    val context = LocalContext.current.applicationContext
    var bitmap by remember(uri) { mutableStateOf(AvatarCache.bitmaps.get(uri)) }
    LaunchedEffect(uri) {
        if (bitmap == null && !AvatarCache.failedRecently(uri)) {
            val loaded = loadAvatar(context, uri)
            if (loaded != null) {
                AvatarCache.bitmaps.put(uri, loaded)
                bitmap = loaded
            } else {
                AvatarCache.markFailed(uri)
            }
        }
    }
    return bitmap
}

/** Glide decodes off the main thread; cancelling the composition cancels the request. */
private suspend fun loadAvatar(context: Context, uri: String): ImageBitmap? =
    suspendCancellableCoroutine { continuation ->
        val sizePx = (AVATAR_PX).toInt()
        val target = object : CustomTarget<Bitmap>(sizePx, sizePx) {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                if (continuation.isActive) continuation.resume(resource.asImageBitmap())
            }

            override fun onLoadFailed(errorDrawable: Drawable?) {
                if (continuation.isActive) continuation.resume(null)
            }

            override fun onLoadCleared(placeholder: Drawable?) = Unit
        }
        val glide = Glide.with(context)
        glide.asBitmap().load(uri).centerCrop().into(target)
        continuation.invokeOnCancellation { glide.clear(target) }
    }

private const val AVATAR_PX = 144f
