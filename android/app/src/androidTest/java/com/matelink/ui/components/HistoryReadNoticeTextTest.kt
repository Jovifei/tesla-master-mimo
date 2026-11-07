package com.matelink.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.matelink.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistoryReadNoticeTextTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unverifiedAndCachedStatesStayVisibleAlongsideRealErrors() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val warning = mutableStateOf("history_detail_source_unverified")
        compose.setContent { MaterialTheme { HistoryReadNotices(false, warning.value) } }
        val cases = listOf(
            "history_detail_source_unverified" to R.string.history_detail_source_unverified,
            "history_detail_total_unverified" to R.string.history_detail_total_unverified,
            "history_detail_cached_source_unverified" to R.string.history_detail_cached_source_unverified,
            "history_detail_cached_total_unverified" to R.string.history_detail_cached_total_unverified,
            "history_detail_cache_corrupt" to R.string.history_detail_cache_corrupt
        )
        for ((code, message) in cases) {
            compose.runOnIdle { warning.value = code }
            compose.onNodeWithText(context.getString(message)).assertIsDisplayed()
        }
    }
}
