package com.glooboo.umessage.testing

import android.app.Instrumentation
import androidx.room.RoomDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry

/**
 * A [MigrationTestHelper] for [databaseClass] that also works on Windows.
 *
 * Room 2.8's SupportSQLiteDriver checks that the file it is asked to open is the one its open helper was configured
 * for by comparing the text after the last '/'. On Windows the requested path uses '\', so the check fails and every
 * createDatabase call throws "This driver is configured to open a database named ...". Reporting the full path as
 * the helper's name makes the two strings equal on every OS; the file that gets opened is unchanged.
 */
fun migrationTestHelper(
    databaseClass: Class<out RoomDatabase>,
    instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation(),
): MigrationTestHelper =
    MigrationTestHelper(instrumentation, databaseClass, emptyList(), FullPathNameOpenHelperFactory())

private class FullPathNameOpenHelperFactory(
    private val delegate: SupportSQLiteOpenHelper.Factory = FrameworkSQLiteOpenHelperFactory(),
) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val helper = delegate.create(configuration)
        val name = configuration.name ?: return helper
        val path = configuration.context.getDatabasePath(name).path
        return object : SupportSQLiteOpenHelper by helper {
            override val databaseName: String = path
        }
    }
}
