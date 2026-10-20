package com.matelink

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MateLinkApplicationProcessTest {
    @Test fun tpmsStartupScheduleBelongsOnlyToMainProcess() {
        assertTrue(shouldScheduleTpmsWorker("com.matelink", "com.matelink"))
        assertFalse(shouldScheduleTpmsWorker("com.matelink", "com.matelink:amap_verification"))
        assertFalse(shouldScheduleTpmsWorker("com.matelink", null))
    }
}
