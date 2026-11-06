package com.glooboo.umessage.ui.thread

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Result of a Glide bitmap load: null while loading, [Failed] when the model could not be decoded. */
sealed interface ImageLoad {
    data class Loaded(val bitmap: Bitmap) : ImageLoad
    data object Failed : ImageLoad
}

/**
 * Loads [model] (a content Uri, file or url) through Glide at no more than [maxWidth] x [maxHeight] pixels.
 * Glide stays the image loader so MMS part Uris, video frames and the disk cache behave as they did in the old list.
 */
@Composable
fun rememberGlideBitmap(model: Any?, maxWidth: Int, maxHeight: Int): State<ImageLoad?> {
    val context = LocalContext.current.applicationContext
    if (model == null) return remember { mutableStateOf(ImageLoad.Failed) }
    return produceState<ImageLoad?>(initialValue = null, model, maxWidth, maxHeight) {
        val target = Glide.with(context)
            .asBitmap()
            .load(model)
            .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
            .downsample(DownsampleStrategy.AT_MOST)
            .dontAnimate()
            .submit(maxWidth, maxHeight)
        try {
            value = withContext(Dispatchers.IO) {
                try {
                    ImageLoad.Loaded(target.get())
                } catch (_: Exception) {
                    ImageLoad.Failed
                }
            }
        } finally {
            if (!target.isDone) target.cancel(true)
        }
    }
}

