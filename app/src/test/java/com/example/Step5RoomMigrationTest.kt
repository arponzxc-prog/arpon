package com.example

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.local.NexaMigrations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Step5RoomMigrationTest {

    private fun mig(from: Int, to: Int) = object : Migration(from, to) {
        override fun migrate(db: SupportSQLiteDatabase) {}
    }

    @Test
    fun currentVersionHasCompleteMigrationChain() {
        assertTrue(
            "Missing Room migrations: ${NexaMigrations.missingSteps(NexaMigrations.CURRENT_VERSION)}",
            NexaMigrations.missingSteps(NexaMigrations.CURRENT_VERSION).isEmpty()
        )
    }

    @Test
    fun detectsMissingStep() {
        val partial = arrayOf<Migration>(mig(1, 2), mig(3, 4))
        assertEquals(listOf(2 to 3), NexaMigrations.missingSteps(4, partial))
    }

    @Test
    fun completeChainHasNoGaps() {
        val full = arrayOf<Migration>(mig(1, 2), mig(2, 3))
        assertTrue(NexaMigrations.missingSteps(3, full).isEmpty())
    }
}
