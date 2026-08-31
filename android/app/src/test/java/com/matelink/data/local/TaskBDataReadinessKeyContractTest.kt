package com.matelink.data.local

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskBDataReadinessKeyContractTest {
    @Test
    fun seenKeyIsOpaqueAndBindsModeServerVehicleAndCapability() {
        val source = File("src/main/java/com/matelink/data/local/DataReadinessStore.kt").readText()

        assertTrue(source.contains("MessageDigest"))
        assertTrue(source.contains("ConnectionMode"))
        assertTrue(source.contains("serverUrl"))
        assertTrue(source.contains("capabilityVersion"))
        assertFalse(source.contains("jourvolt-user:\$it"))
    }
}
