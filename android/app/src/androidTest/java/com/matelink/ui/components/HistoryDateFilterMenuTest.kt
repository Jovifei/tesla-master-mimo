package com.matelink.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.matelink.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HistoryDateFilterMenuTest {
    @get:Rule val rule = createComposeRule()

    @Test fun selectingAWindowOrCustomDateInvokesTheIntendedAction() {
        val choices = listOf("Week", "Month", "Three months", "Year", "All time")
        var selected = -1
        var customOpened = false
        rule.setContent {
            var current by remember { mutableIntStateOf(1) }
            MaterialTheme {
                HistoryDateFilterMenu(choices[current], choices, current, "history-date-test", {
                    current = it
                    selected = it
                }, { customOpened = true })
            }
        }

        choices.indices.forEach { index ->
            rule.onNodeWithTag("history-date-test").performClick()
            rule.onNodeWithText(choices[index]).performClick()
            rule.runOnIdle { assertEquals(index, selected) }
        }

        rule.onNodeWithTag("history-date-test").performClick()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        rule.onNodeWithText(context.getString(R.string.history_choose_date)).performClick()
        rule.runOnIdle { assertEquals(true, customOpened) }
    }
}
