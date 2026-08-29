package com.matelink.ui.navigation

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import com.matelink.R
import com.matelink.ui.theme.swissPalette

/**
 * The four L1 bottom-navigation destinations for the MateLink shell.
 *
 * `Drives`, `Charges` and `More` carry no [Screen] instance here because their
 * routes require a `carId` that is only known at runtime; [navigateToTopLevel]
 * resolves the active car when a tab is tapped.
 */
internal enum class TopLevelDestination(
    val labelRes: Int,
    val icon: ImageVector,
    val iconKey: TopLevelIconKey
) {
    Dashboard(R.string.nav_dashboard, Icons.Default.Speed, TopLevelIconKey.VEHICLE),
    Drives(R.string.nav_drives, Icons.Default.Route, TopLevelIconKey.ROUTE),
    Charges(R.string.nav_charges, Icons.Default.Bolt, TopLevelIconKey.ENERGY),
    More(R.string.nav_more, Icons.Default.Analytics, TopLevelIconKey.ANALYSIS)
}

private fun topLevelRouteName(dest: TopLevelDestination): String = when (dest) {
    TopLevelDestination.Dashboard -> Screen.Dashboard::class.qualifiedName!!
    TopLevelDestination.Drives -> Screen.Drives::class.qualifiedName!!
    TopLevelDestination.Charges -> Screen.Charges::class.qualifiedName!!
    TopLevelDestination.More -> Screen.More::class.qualifiedName!!
}

private fun currentTopLevelDestination(route: String?): TopLevelDestination? {
    if (route == null) return null
    val normalizedRoute = route.replace('$', '.')
    val routeLeaf = normalizedRoute
        .substringBefore('/')
        .substringBefore('?')
        .substringAfterLast('.')
    return TopLevelDestination.entries.firstOrNull { dest ->
        val name = topLevelRouteName(dest)
        // Type-safe routes use the FQCN as the base route; data-class destinations
        // may append path or query placeholders. Kotlin/JVM nested routes may use
        // '$' in the runtime string, so normalize that form before matching. R8
        // can change the route prefix, so the stable serial-name leaf is a fallback.
        normalizedRoute == name || normalizedRoute.startsWith("$name/") ||
            normalizedRoute.startsWith("$name?") || routeLeaf == dest.name
    }
}

private fun navigateToTopLevel(
    navController: NavController,
    dest: TopLevelDestination,
    currentCarId: Int
) {
    val screen: Screen = when (dest) {
        TopLevelDestination.Dashboard -> Screen.Dashboard
        TopLevelDestination.Drives -> Screen.Drives(currentCarId, null)
        TopLevelDestination.Charges -> Screen.Charges(currentCarId, null)
        TopLevelDestination.More -> Screen.More(currentCarId, null)
    }
    // saveState/restoreState are intentionally NOT used here: the Drives,
    // Charges and More top-level routes carry `carId`, and restoring a saved
    // back-stack entry would resurrect the previously-selected car's args after
    // the active car changes. Popping to the start destination and navigating
    // fresh guarantees the current [currentCarId] is applied on every tab tap.
    navController.navigate(screen) {
        popUpTo(navController.graph.findStartDestination().id)
        launchSingleTop = true
    }
}

/**
 * Swiss "Precision Minimalist" bottom navigation bar.
 *
 * Shown only on the four top-level destinations; returns nothing on L2/L3
 * detail screens so those keep their own full-screen Scaffold + back affordance.
 * Colors come from [swissPalette] so the bar follows the system theme: pure-white
 * surface with a 1px hairline, near-black selected state and muted grey
 * unselected state in light mode; the dark counterparts in dark mode.
 */
@Composable
fun MateLinkBottomBar(
    navController: NavController,
    currentCarId: Int
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val selected = currentTopLevelDestination(currentRoute) ?: return
    val palette = swissPalette()

    Column {
        HorizontalDivider(color = palette.outline, thickness = 1.dp)
        NavigationBar(
            containerColor = palette.surface,
            tonalElevation = 0.dp
        ) {
            TopLevelDestination.entries.forEach { dest ->
                val label = stringResource(dest.labelRes)
                NavigationBarItem(
                    selected = dest == selected,
                    onClick = { navigateToTopLevel(navController, dest, currentCarId) },
                    icon = {
                        val iconScale by animateFloatAsState(
                            targetValue = PearlDriveMotion.iconScale(dest == selected),
                            animationSpec = tween(
                                durationMillis = PearlDriveMotion.NavigationSelectionDurationMillis,
                                easing = FastOutSlowInEasing
                            ),
                            label = "pearl_drive_nav_icon_scale"
                        )
                        Icon(
                            imageVector = dest.icon,
                            contentDescription = label,
                            modifier = Modifier
                                .size(24.dp)
                                .graphicsLayer {
                                    scaleX = iconScale
                                    scaleY = iconScale
                                }
                        )
                    },
                    label = { Text(label) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = MaterialTheme.colorScheme.primary,
                        selectedTextColor = MaterialTheme.colorScheme.primary,
                        unselectedIconColor = palette.muted,
                        unselectedTextColor = palette.muted,
                        indicatorColor = MaterialTheme.colorScheme.primaryContainer
                    )
                )
            }
        }
    }
}

/**
 * The MateLink app entry shell. Rendered by [com.matelink.MainActivity].
 *
 * Delegates all routing to [NavGraph], which owns the type-safe [NavHost] and
 * wraps it in the bottom-bar [Scaffold]. The launch [intent] is forwarded so
 * the notification deep-link path inside [NavGraph] becomes live.
 */
@Composable
fun MateLinkNavHost(intent: Intent? = null) {
    NavGraph(intent = intent)
}
