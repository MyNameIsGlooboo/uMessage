package com.glooboo.umessage.helpers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import org.fossify.commons.extensions.getCompressionFormat
import org.fossify.commons.extensions.getMyFileUri
import org.fossify.commons.helpers.ensureBackgroundThread
import com.glooboo.umessage.extensions.extension
import com.glooboo.umessage.extensions.getExtensionFromMimeType
import com.glooboo.umessage.extensions.getFileSizeFromUri
import com.glooboo.umessage.extensions.isImageMimeType
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * Compress image to a given size based on
 * [Compressor](https://github.com/zetbaitsu/Compressor/)
 * */
class ImageCompressor(private val context: Context) {
    private val contentResolver = context.contentResolver
    private val outputDirectory = File(context.cacheDir, "compressed").apply {
        if (!exists()) {
            mkdirs()
        }
    }

    private val minQuality = 30
    private val minResolution = 56
    private val scaleStepFactor = 0.6f // increase for more accurate file size at the cost increased computation

    fun compressImage(uri: Uri, compressSize: Long, lossy: Boolean = compressSize < FILE_SIZE_1_MB, callback: (compressedFileUri: Uri?) -> Unit) {
        ensureBackgroundThread {
            try {
                val fileSize = context.getFileSizeFromUri(uri)
                if (fileSize > compressSize) {
                    val mimeType = contentResolver.getType(uri)!!
                    if (mimeType.isImageMimeType()) {
                        var imageFile = File(outputDirectory, compressedFileName(mimeType.getExtensionFromMimeType()))
                        // Streamed to the file: reading a large photo into a byte array could run out of memory.
                        contentResolver.openInputStream(uri)!!.use { input ->
                            imageFile.outputStream().use { input.copyTo(it) }
                        }
                        val bitmap = loadBitmap(imageFile)
                        val format = if (lossy) {
                            Bitmap.CompressFormat.JPEG
                        } else {
                            imageFile.path.getCompressionFormat()
                        }

                        // This quality approximation mostly works for smaller images but will fail with larger images.
                        val compressionRatio = compressSize / fileSize.toDouble()
                        val quality = maxOf((compressionRatio * 100).roundToInt(), minQuality)
                        imageFile = overWrite(imageFile, bitmap, format = format, quality = quality)

                        // Even the highest quality images start to look ugly if we use 10 as the minimum quality,
                        // so we better save some image quality and change resolution instead. This is time consuming
                        // and mostly needed for very large images. Since there's no reliable way to predict the
                        // required resolution, we'll just iterate and find the best result.
                        if (imageFile.length() > compressSize) {
                            var scaledWidth = bitmap.width
                            var scaledHeight = bitmap.height

                            while (imageFile.length() > compressSize) {
                                scaledWidth = (scaledWidth * scaleStepFactor).roundToInt()
                                scaledHeight = (scaledHeight * scaleStepFactor).roundToInt()
                                if (scaledHeight < minResolution && scaledWidth < minResolution) {
                                    break
                                }

                                imageFile = decodeSampledBitmapFromFile(imageFile, scaledWidth, scaledHeight).run {
                                    determineImageRotation(imageFile, bitmap = this).run {
                                        overWrite(imageFile, bitmap = this, format = format, quality = quality)
                                    }
                                }
                            }
                        }

                        callback.invoke(context.getMyFileUri(imageFile))
                    } else {
                        callback.invoke(null)
                    }
                } else {
                    // no need to compress since the file is less than the compress size
                    callback.invoke(if (context.composerPrefs.removePhotoLocation) withoutLocation(uri) else uri)
                }
            } catch (e: Exception) {
                callback.invoke(null)
            } catch (e: OutOfMemoryError) {
                // Shown as "could not compress" and the attachment is dropped, instead of the app closing.
                callback.invoke(null)
            }
        }
    }

    /**
     * I09: a copy of the picture without its location and camera details (the user's original is never touched).
     * Pictures that cannot carry any, and any picture that cannot be cleaned, go on as they are.
     */
    private fun withoutLocation(uri: Uri): Uri {
        val mimeType = contentResolver.getType(uri).orEmpty()
        val cleanup = ExifStripper.cleanupFor(mimeType)
        if (cleanup == PictureCleanup.Keep) return uri
        val copy = File.createTempFile("image_", ".jpg", outputDirectory)
        contentResolver.openInputStream(uri)?.use { input -> copy.outputStream().use { input.copyTo(it) } }
        val cleaned = when (cleanup) {
            PictureCleanup.ClearTags -> ExifStripper.strip(copy)
            else -> runCatching {
                overWrite(copy, loadBitmap(copy), Bitmap.CompressFormat.JPEG, REENCODE_QUALITY)
            }.isSuccess
        }
        if (cleaned) return context.getMyFileUri(copy)
        copy.delete()
        return uri
    }

    private fun overWrite(imageFile: File, bitmap: Bitmap, format: Bitmap.CompressFormat = imageFile.path.getCompressionFormat(), quality: Int = 100): File {
        val result = if (format == imageFile.path.getCompressionFormat()) {
            imageFile
        } else {
            File("${imageFile.absolutePath.substringBeforeLast(".")}.${format.extension()}")
        }
        imageFile.delete()
        saveBitmap(bitmap, result, format, quality)
        return result
    }

    private fun saveBitmap(bitmap: Bitmap, destination: File, format: Bitmap.CompressFormat = destination.path.getCompressionFormat(), quality: Int = 100) {
        destination.parentFile?.mkdirs()
        var fileOutputStream: FileOutputStream? = null
        try {
            fileOutputStream = FileOutputStream(destination.absolutePath)
            bitmap.compress(format, quality, fileOutputStream)
        } finally {
            fileOutputStream?.run {
                flush()
                close()
            }
        }
    }

    /**
     * Decodes the photo at a reduced size: a 200 MP camera photo decoded in full needs about 800 MB. MMS limits are
     * at most a few hundred KB to a few MB, so a long edge of [MAX_DECODE_EDGE] pixels loses nothing visible.
     */
    private fun loadBitmap(imageFile: File): Bitmap {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(imageFile.absolutePath, options)
        options.inSampleSize = sampleSizeFor(options.outWidth, options.outHeight, MAX_DECODE_EDGE)
        options.inJustDecodeBounds = false
        val decoded = BitmapFactory.decodeFile(imageFile.absolutePath, options)
            ?: throw IllegalArgumentException("Not a decodable image")
        return determineImageRotation(imageFile, decoded)
    }

    private fun determineImageRotation(imageFile: File, bitmap: Bitmap): Bitmap {
        val exif = ExifInterface(imageFile.absolutePath)
        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)
        val matrix = Matrix()
        when (orientation) {
            6 -> matrix.postRotate(90f)
            3 -> matrix.postRotate(180f)
            8 -> matrix.postRotate(270f)
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        // Free the unrotated copy straight away instead of keeping two full bitmaps alive.
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    private fun decodeSampledBitmapFromFile(imageFile: File, reqWidth: Int, reqHeight: Int): Bitmap {
        return BitmapFactory.Options().run {
            inJustDecodeBounds = true
            BitmapFactory.decodeFile(imageFile.absolutePath, this)

            inSampleSize = calculateInSampleSize(this, reqWidth, reqHeight)

            inJustDecodeBounds = false
            BitmapFactory.decodeFile(imageFile.absolutePath, this)
        }
    }

    companion object {
        /** Longest edge, in pixels, a photo is decoded at before it is compressed. */
        const val MAX_DECODE_EDGE = 2048

        /** Quality of a picture saved again as a JPEG only to drop its tags. */
        private const val REENCODE_QUALITY = 95

        /** The smallest power of two that brings the longer of [width] and [height] down to [maxEdge] or less. */
        fun sampleSizeFor(width: Int, height: Int, maxEdge: Int): Int {
            var sample = 1
            val longest = maxOf(width, height)
            while (longest / sample > maxEdge) sample *= 2
            return sample
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        // Raw height and width of image
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {

            val halfHeight: Int = height / 2
            val halfWidth: Int = width / 2

            // Calculate the largest inSampleSize value that is a power of 2 and keeps both
            // height and width larger than the requested height and width.
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }

        return inSampleSize
    }
}
