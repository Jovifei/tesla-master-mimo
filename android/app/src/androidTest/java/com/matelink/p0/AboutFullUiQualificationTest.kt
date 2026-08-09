package com.matelink.p0

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.matelink.MainActivity
import com.matelink.R
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class AboutFullUiQualificationTest {
    @get:Rule val composeRule = createEmptyComposeRule()

    @Test fun threePublicInfoEntriesAreClickedWithoutLeavingTheApp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        runBlocking { prepareQualificationState(context) }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            composeRule.navigateToAbout(context)
            listOf("helpLink", "legalLink", "changelogLink").forEach { tag ->
                composeRule.onNodeWithTag(tag).performScrollTo().performClick()
                composeRule.waitForText(context.getString(R.string.about_public_pages_unconfigured))
            }
            scenario.onActivity { activity -> check(!activity.isFinishing) }
            composeRule.onNodeWithTag("helpLink").assertExists()
        }
    }
}
