package com.matelink.data.local

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCCompositeHistoryContractTest {
    @Test
    fun historyEntitiesAndMigrationUseVehicleScopedCompositeKeys() {
        val entitySources = listOf(
            File("src/main/java/com/matelink/data/local/entity/DriveSummary.kt"),
            File("src/main/java/com/matelink/data/local/entity/ChargeSummary.kt"),
            File("src/main/java/com/matelink/data/local/entity/DriveDetailAggregate.kt"),
            File("src/main/java/com/matelink/data/local/entity/ChargeDetailAggregate.kt")
        ).joinToString("\n") { it.readText() }
        val databaseSource = File("src/main/java/com/matelink/data/local/StatsDatabase.kt").readText()

        assertTrue(entitySources.contains("primaryKeys = [\"carId\", \"driveId\"]"))
        assertTrue(entitySources.contains("primaryKeys = [\"carId\", \"chargeId\"]"))
        assertTrue(databaseSource.contains("version = 19"))
        assertTrue(databaseSource.contains("MIGRATION_17_18"))
        assertTrue(databaseSource.contains("MIGRATION_18_19"))
    }
}
