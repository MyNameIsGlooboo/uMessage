package com.glooboo.umessage.ui.thread

import android.Manifest
import android.database.MatrixCursor
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** I69: which media permission state the row sees, what is asked for, and how the newest pictures are read. */
@RunWith(AndroidJUnit4::class)
class RecentPhotosTest {
    private val images = Manifest.permission.READ_MEDIA_IMAGES
    private val selected = Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    private val storage = Manifest.permission.READ_EXTERNAL_STORAGE

    @Test
    fun fullAccessNeedsTheMediaPermissionOnNewPhonesAndStorageOnOldOnes() {
        assertThat(RecentPhotosAccess.resolve(34) { it == images }).isEqualTo(PhotoAccess.Full)
        assertThat(RecentPhotosAccess.resolve(33) { it == images }).isEqualTo(PhotoAccess.Full)
        assertThat(RecentPhotosAccess.resolve(32) { it == storage }).isEqualTo(PhotoAccess.Full)
        assertThat(RecentPhotosAccess.resolve(32) { it == images }).isEqualTo(PhotoAccess.None)
    }

    @Test
    fun choosingPhotosOnAndroid14IsPartialAccess() {
        assertThat(RecentPhotosAccess.resolve(34) { it == selected }).isEqualTo(PhotoAccess.Partial)
        assertThat(RecentPhotosAccess.resolve(33) { it == selected }).isEqualTo(PhotoAccess.None)
        assertThat(RecentPhotosAccess.resolve(34) { false }).isEqualTo(PhotoAccess.None)
    }

    @Test
    fun thePromptAsksForWhatTheAndroidVersionKnows() {
        assertThat(RecentPhotosAccess.permissions(34)).asList().containsExactly(images, selected)
        assertThat(RecentPhotosAccess.permissions(33)).asList().containsExactly(images)
        assertThat(RecentPhotosAccess.permissions(30)).asList().containsExactly(storage)
    }

    @Test
    fun idsKeepTheCursorOrderAndStopAtTheLimit() {
        val cursor = MatrixCursor(arrayOf("_id")).apply { listOf(9L, 7L, 5L, 3L).forEach { addRow(arrayOf<Any>(it)) } }
        assertThat(RecentPhotosLoader.idsFrom(cursor, 3)).containsExactly(9L, 7L, 5L).inOrder()
    }

    @Test
    fun noPicturesGiveAnEmptyList() {
        assertThat(RecentPhotosLoader.idsFrom(MatrixCursor(arrayOf("_id")), 12)).isEmpty()
    }
}
