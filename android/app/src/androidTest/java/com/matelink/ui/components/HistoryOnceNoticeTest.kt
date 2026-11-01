package com.matelink.ui.components

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.matelink.R
import org.junit.Rule
import org.junit.Test

class HistoryOnceNoticeTest {
    @get:Rule val rule = createComposeRule()

    @Test fun acknowledgementPersistsAcrossReentryAndIsScopedToTheVehicle() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("history_notice_acknowledgements", Context.MODE_PRIVATE)
        prefs.edit().remove("archive_v1_-90001").remove("archive_v1_-90002").commit()
        val vehicle = mutableStateOf<Int?>(-90001)
        rule.setContent { MaterialTheme { HistoryArchiveNotice(true, vehicle.value) } }
        val button = context.getString(R.string.got_it)
        rule.onNodeWithText(button).assertExists().performClick()
        rule.runOnIdle { vehicle.value = null }
        rule.runOnIdle { vehicle.value = -90001 }
        rule.onNodeWithText(button).assertDoesNotExist()
        rule.runOnIdle { vehicle.value = -90002 }
        rule.onNodeWithText(button).assertExists()
    }
}
