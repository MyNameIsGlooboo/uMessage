package com.glooboo.umessage.helpers

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/** I09: location and camera details leave a JPEG; the picture, its orientation and the original stay. */
@RunWith(AndroidJUnit4::class)
class ExifStripperTest {
    @get:Rule
    val folder = TemporaryFolder()

    /** An invented geotagged photo: a small coloured JPEG with a position, a camera and a rotation tag. */
    private fun geotaggedJpeg(name: String = "photo.jpg"): File {
        val file = folder.newFile(name)
        val bitmap = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 80, 40)) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val exif = ExifInterface(file.absolutePath)
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, "35/1,41/1,0/1")
        exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "51/1,20/1,0/1")
        exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
        exif.setAttribute(ExifInterface.TAG_MAKE, "InventedCam")
        exif.setAttribute(ExifInterface.TAG_MODEL, "Model 1")
        exif.setAttribute(ExifInterface.TAG_DATETIME, "2026:01:02 03:04:05")
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
        exif.saveAttributes()
        return file
    }

    @Test
    fun fixtureReallyCarriesTheTags() {
        val exif = ExifInterface(geotaggedJpeg().absolutePath)
        assertThat(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)).isNotNull()
        assertThat(exif.getAttribute(ExifInterface.TAG_MAKE)).isEqualTo("InventedCam")
    }

    @Test
    fun stripRemovesLocationAndCameraButKeepsOrientationAndPixels() {
        val file = geotaggedJpeg()
        val before = BitmapFactory.decodeFile(file.absolutePath)

        assertThat(ExifStripper.strip(file)).isTrue()

        val exif = ExifInterface(file.absolutePath)
        assertThat(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)).isNull()
        assertThat(exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE)).isNull()
        assertThat(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE_REF)).isNull()
        assertThat(exif.getAttribute(ExifInterface.TAG_MAKE)).isNull()
        assertThat(exif.getAttribute(ExifInterface.TAG_MODEL)).isNull()
        assertThat(exif.getAttribute(ExifInterface.TAG_DATETIME)).isNull()
        assertThat(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
            .isEqualTo(ExifInterface.ORIENTATION_ROTATE_90)
        assertThat(BitmapFactory.decodeFile(file.absolutePath).sameAs(before)).isTrue()
    }

    @Test
    fun theOriginalIsLeftAloneWhenACopyIsStripped() {
        val original = geotaggedJpeg()
        val copy = folder.newFile("copy.jpg")
        original.copyTo(copy, overwrite = true)
        val originalBytes = original.readBytes()

        ExifStripper.strip(copy)

        assertThat(original.readBytes()).isEqualTo(originalBytes)
        assertThat(ExifInterface(original.absolutePath).getAttribute(ExifInterface.TAG_GPS_LATITUDE)).isNotNull()
    }

    @Test
    fun pngAndOtherFilesPassThroughUnchanged() {
        val png = folder.newFile("picture.png")
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).also { bitmap ->
            png.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val bytes = png.readBytes()

        assertThat(ExifStripper.strip(png)).isFalse()
        assertThat(png.readBytes()).isEqualTo(bytes)
        assertThat(ExifStripper.cleanupFor("image/png")).isEqualTo(PictureCleanup.Keep)
        assertThat(ExifStripper.cleanupFor("image/gif")).isEqualTo(PictureCleanup.Keep)
        assertThat(ExifStripper.cleanupFor("image/JPEG")).isEqualTo(PictureCleanup.ClearTags)
        assertThat(ExifStripper.cleanupFor("image/heic")).isEqualTo(PictureCleanup.Reencode)
    }

    @Test
    fun aCorruptFileNeverThrowsAndIsUnchangedWhenItCannotBeCleaned() {
        val corrupt = folder.newFile("broken.jpg")
        corrupt.writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x01, 0x02, 0x03))
        val bytes = corrupt.readBytes()

        val cleaned = ExifStripper.strip(corrupt)

        if (!cleaned) assertThat(corrupt.readBytes()).isEqualTo(bytes)
        assertThat(ExifStripper.strip(folder.newFile("empty.jpg"))).isFalse()
    }
}
