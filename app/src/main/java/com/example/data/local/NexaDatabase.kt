package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import com.example.data.local.dao.BuildArtifactDao
import com.example.data.local.dao.BuildDao
import com.example.data.local.dao.GitHubConnectionDao
import com.example.data.local.dao.NotificationDao
import com.example.data.local.dao.ProjectDao
import com.example.data.local.dao.RecentFileDao
import com.example.data.local.entity.BuildArtifactEntity
import com.example.data.local.entity.BuildEntity
import com.example.data.local.entity.GitHubConnectionEntity
import com.example.data.local.entity.NotificationEntity
import com.example.data.local.entity.ProjectEntity
import com.example.data.local.entity.RecentFileEntity

@Database(
    entities = [
        ProjectEntity::class,
        BuildEntity::class,
        BuildArtifactEntity::class,
        GitHubConnectionEntity::class,
        NotificationEntity::class,
        RecentFileEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class NexaDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun buildDao(): BuildDao
    abstract fun buildArtifactDao(): BuildArtifactDao
    abstract fun gitHubConnectionDao(): GitHubConnectionDao
    abstract fun notificationDao(): NotificationDao
    abstract fun recentFileDao(): RecentFileDao

    companion object {
        @Volatile
        private var INSTANCE: NexaDatabase? = null

        fun getInstance(context: Context): NexaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NexaDatabase::class.java,
                    "nexa_ide.db"
                )
                    .addMigrations(*NexaMigrations.ALL)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

/**
 * Room migrations. RULE: whenever @Database(version) is bumped, add a Migration(old, new) here
 * (never use fallbackToDestructiveMigration - user projects/build history must not be lost).
 * Schemas are exported to app/schemas for MigrationTestHelper-based tests.
 */
object NexaMigrations {
    const val CURRENT_VERSION = 1

    val ALL: Array<Migration> = arrayOf(
        // Example for the next bump (keep as a template):
        // object : Migration(1, 2) {
        //     override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        //         db.execSQL("ALTER TABLE projects ADD COLUMN newColumn TEXT NOT NULL DEFAULT ''")
        //     }
        // }
    )

    /** Returns the (from,to) steps that are missing so that every version 1..[currentVersion] is reachable. */
    fun missingSteps(currentVersion: Int, migrations: Array<Migration> = ALL): List<Pair<Int, Int>> {
        val have = migrations.map { it.startVersion to it.endVersion }.toSet()
        return (1 until currentVersion).map { it to it + 1 }.filterNot { it in have }
    }
}
