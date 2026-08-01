package com.matelink.ui.screens.map

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmapVerificationBackContractTest {
    @Test
    fun verificationUsesLifecycleAwareBackDispatcher() {
        val source = File(
            "src/main/java/com/matelink/ui/screens/map/AmapKeyVerificationActivity.kt"
        ).readText()

        assertFalse(source.contains("override fun onBackPressed"))
        assertTrue(source.contains("onBackPressedDispatcher.addCallback"))
        assertTrue(source.contains("complete(Activity.RESULT_CANCELED)"))
    }
}
