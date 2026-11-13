package com.matelink.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.matelink.R
import com.matelink.ui.theme.MateLinkTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MoreNavigationAndroidTest {
    @get:Rule val compose = createComposeRule()

    @Test fun moreOpensTheHubEvenWithAPreviousSavedTripsStack() {
        lateinit var controller: NavHostController
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        compose.setContent {
            MateLinkTheme {
                controller = rememberNavController()
                Scaffold(bottomBar = { MateLinkBottomBar(controller, 1) }) { padding ->
                    Box(Modifier.padding(padding)) {
                        NavHost(controller, startDestination = Screen.Dashboard) {
                            composable<Screen.Dashboard> { Text("dashboard-fixture") }
                            composable<Screen.Drives> { Text("stacked-days-fixture") }
                            composable<Screen.Charges> { Text("charge-fixture") }
                            composable<Screen.More> {
                                Button(onClick = { controller.navigate(Screen.DriveHistory(1)) }) { Text("history-link") }
                            }
                            composable<Screen.DriveHistory> {
                                Button(onClick = { controller.popBackStack() }) { Text("history-back") }
                            }
                        }
                    }
                }
            }
        }
        fun tapTab(label: Int) = compose.onNodeWithText(context.getString(label)).performTouchInput { click() }
        tapTab(R.string.nav_drives)
        compose.onNodeWithText("stacked-days-fixture").assertIsDisplayed()
        tapTab(R.string.nav_more)
        compose.onNodeWithText("history-link").assertIsDisplayed()
        compose.onNodeWithText("history-link").performClick()
        compose.onNodeWithText("history-back").assertIsDisplayed().performClick()
        compose.onNodeWithText("history-link").assertIsDisplayed()
        compose.runOnIdle { controller.navigate(Screen.Drives(1)) } // Legacy More route.
        tapTab(R.string.nav_charges)
        compose.onNodeWithText("charge-fixture").assertIsDisplayed()
        tapTab(R.string.nav_more)
        compose.onNodeWithText("history-link").assertIsDisplayed()
    }
}
