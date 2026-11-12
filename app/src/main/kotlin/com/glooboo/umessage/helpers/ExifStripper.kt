package com.glooboo.umessage.helpers

import android.media.ExifInterface
import java.io.File
import java.io.IOException

/** What a picture needs before it is sent without its hidden details. */
enum class PictureCleanup {
    /** A JPEG: its tags are cleared in place. */
    ClearTags,

    /** HEIC, WebP and the like carry tags this phone cannot edit: the picture is saved again as a JPEG. */
    Reencode,

    /** PNG, GIF and everything else carry no location. */
    Keep,
}

/**
 * Removes the location and the details that identify a camera or a person from a JPEG (I09). The picture itself
 * and its orientation stay: a rotated photo must not turn sideways.
 */
object ExifStripper {
    private const val JPEG_MAGIC_FIRST = 0xFF
    private const val JPEG_MAGIC_SECOND = 0xD8
    private val REENCODE_TYPES = setOf("image/heic", "image/heif", "image/avif", "image/webp")

    private val CLEARED_TAGS = listOf(
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        ExifInterface.TAG_GPS_PROCESSING_METHOD, ExifInterface.TAG_GPS_AREA_INFORMATION,
        ExifInterface.TAG_GPS_SPEED, ExifInterface.TAG_GPS_SPEED_REF,
        ExifInterface.TAG_GPS_TRACK, ExifInterface.TAG_GPS_TRACK_REF,
        ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
        ExifInterface.TAG_GPS_DEST_LATITUDE, ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
        ExifInterface.TAG_GPS_DEST_LONGITUDE, ExifInterface.TAG_GPS_DEST_LONGITUDE_REF,
        ExifInterface.TAG_GPS_DEST_BEARING, ExifInterface.TAG_GPS_DEST_BEARING_REF,
        ExifInterface.TAG_GPS_DEST_DISTANCE, ExifInterface.TAG_GPS_DEST_DISTANCE_REF,
        ExifInterface.TAG_GPS_DOP, ExifInterface.TAG_GPS_MAP_DATUM, ExifInterface.TAG_GPS_SATELLITES,
        ExifInterface.TAG_GPS_STATUS, ExifInterface.TAG_GPS_MEASURE_MODE, ExifInterface.TAG_GPS_DIFFERENTIAL,
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE, ExifInterface.TAG_ARTIST,
        ExifInterface.TAG_COPYRIGHT, ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_DATETIME_DIGITIZED, ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        ExifInterface.TAG_SUBSEC_TIME_DIGITIZED, ExifInterface.TAG_MAKER_NOTE, ExifInterface.TAG_USER_COMMENT,
        ExifInterface.TAG_IMAGE_UNIQUE_ID, ExifInterface.TAG_IMAGE_DESCRIPTION,
        "CameraOwnerName", "BodySerialNumber", "LensSerialNumber", "LensMake", "LensModel",
    )

    fun cleanupFor(mimeType: String): PictureCleanup = when (mimeType.lowercase()) {
        "image/jpeg", "image/jpg" -> PictureCleanup.ClearTags
        in REENCODE_TYPES -> PictureCleanup.Reencode
        else -> PictureCleanup.Keep
    }

    /** True when [file] starts like a JPEG. */
    fun isJpeg(file: File): Boolean = try {
        file.inputStream().use { it.read() == JPEG_MAGIC_FIRST && it.read() == JPEG_MAGIC_SECOND }
    } catch (_: IOException) {
        false
    }

    /**
     * Clears the tags of the JPEG [file] in place. The Exif block (with its embedded thumbnail), XMP, IPTC and
     * comments are dropped; only the orientation is written back. False when it is no JPEG or could not be saved
     * (left as it was).
     */
    fun strip(file: File): Boolean {
        if (!isJpeg(file)) return false
        return try {
            val orientation = ExifInterface(file.absolutePath)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
            val rewritten = dropMetadataSegments(file)
            if (rewritten) {
                if (orientation != ExifInterface.ORIENTATION_UNDEFINED &&
                    orientation != ExifInterface.ORIENTATION_NORMAL
                ) {
                    val exif = ExifInterface(file.absolutePath)
                    exif.setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                    exif.saveAttributes()
                }
            } else {
                // Unusual layout: clear the listed tags in place instead.
                val exif = ExifInterface(file.absolutePath)
                CLEARED_TAGS.forEach { exif.setAttribute(it, null) }
                exif.saveAttributes()
            }
            true
        } catch (_: IOException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    private const val MIN_JPEG_SIZE = 4
    private const val SOI_LENGTH = 2
    private const val SEGMENT_HEADER = 4
    private const val LENGTH_FIELD = 2
    private const val BYTE_MASK = 0xFF
    private const val LENGTH_HIGH = 2
    private const val LENGTH_LOW = 3
    private const val MARKER_PREFIX = 0xFF
    private const val MARKER_SOS = 0xDA
    private const val MARKER_APP1 = 0xE1
    private const val MARKER_APP13 = 0xED
    private const val MARKER_COMMENT = 0xFE

    /**
     * Copies [file] without its Exif/XMP (APP1), IPTC (APP13) and comment segments. False, with [file] untouched,
     * when the markers do not parse.
     */
    internal fun dropMetadataSegments(file: File): Boolean {
        val result = withoutMetadata(file.readBytes()) ?: return false
        file.writeBytes(result)
        return true
    }

    private fun withoutMetadata(bytes: ByteArray): ByteArray? {
        if (bytes.size < MIN_JPEG_SIZE) return null
        val out = java.io.ByteArrayOutputStream(bytes.size)
        out.write(bytes, 0, SOI_LENGTH) // SOI
        var i = SOI_LENGTH
        while (i + SEGMENT_HEADER <= bytes.size) {
            if (bytes[i] != MARKER_PREFIX.toByte()) return null
            val marker = bytes[i + 1].toInt() and BYTE_MASK
            if (marker == MARKER_PREFIX) { i++; continue } // fill byte
            if (marker == MARKER_SOS) { // start of scan: the rest is picture data
                out.write(bytes, i, bytes.size - i)
                return out.toByteArray()
            }
            val length = segmentLength(bytes, i)
            val end = i + LENGTH_FIELD + length
            if (length < LENGTH_FIELD || end > bytes.size) return null
            val drop = marker == MARKER_APP1 || marker == MARKER_APP13 || marker == MARKER_COMMENT
            if (!drop) out.write(bytes, i, end - i)
            i = end
        }
        return null
    }

    private fun segmentLength(bytes: ByteArray, at: Int): Int =
        ((bytes[at + LENGTH_HIGH].toInt() and BYTE_MASK) shl Byte.SIZE_BITS) or
            (bytes[at + LENGTH_LOW].toInt() and BYTE_MASK)
}
