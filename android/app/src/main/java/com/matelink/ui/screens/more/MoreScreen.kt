package com.matelink.ui.screens.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.matelink.R
import com.matelink.ui.theme.MateLinkTheme
import com.matelink.ui.theme.swissPalette

private data class MoreAction(
    val icon: ImageVector,
    val title: String,
    val onClick: () -> Unit
)

/**
 * L1 "More" hub — the fourth bottom-nav tab.
 *
 * A lightweight, white-minimal (Stitch Precision Minimalist) navigation hub that
 * groups the analysis / system entries that already exist in the repo but were
 * unreachable from the old 4-tab shell. It deliberately does no data fetching of
 * its own: it only routes to existing screens, passing the active [carId].
 *
 * Groups:
 *  - Data analysis: Statistics, Battery health, Mileage, Trips
 *  - Reports: Annual report, export, vehicle preview, current charge
 *  - System: Software updates, Sentry history, Settings, About
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    carId: Int,
    onNavigateToStats: (carId: Int) -> Unit,
    onNavigateToBattery: (carId: Int) -> Unit,
    onNavigateToMileage: (carId: Int) -> Unit,
    onNavigateToDrives: (carId: Int) -> Unit,
    onNavigateToLongTrips: (carId: Int) -> Unit = {},
    onNavigateToUpdates: (carId: Int) -> Unit,
    onNavigateToSentryHistory: (carId: Int) -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToEfficiency: (carId: Int) -> Unit = {},
    onNavigateToCost: (carId: Int) -> Unit = {},
    onNavigateToRange: (carId: Int) -> Unit = {},
    onNavigateToVampire: (carId: Int) -> Unit = {},
    onNavigateToTimeline: (carId: Int) -> Unit = {},
    onNavigateToAnnualReport: (carId: Int) -> Unit = {},
    onNavigateToExport: (carId: Int) -> Unit = {}
) {
    val palette = swissPalette()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_more), fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 8.dp,
                end = 16.dp,
                bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.VerifiedUser,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(R.string.more_status_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = stringResource(R.string.more_status_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                        )
                        }
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.more_section_analysis)) }
            item {
                MoreActionGrid(
                    actions = listOf(
                        MoreAction(Icons.Default.Speed, stringResource(R.string.more_item_statistics)) {
                            onNavigateToStats(carId)
                        },
                        MoreAction(Icons.Default.BatteryStd, stringResource(R.string.more_item_battery_health)) {
                            onNavigateToBattery(carId)
                        },
                        MoreAction(Icons.Default.Map, stringResource(R.string.more_item_mileage)) {
                            onNavigateToMileage(carId)
                        },
                        MoreAction(Icons.Default.History, stringResource(R.string.more_item_trips)) {
                            onNavigateToDrives(carId)
                        },
                        MoreAction(Icons.Default.Route, stringResource(R.string.more_item_long_trips)) {
                            onNavigateToLongTrips(carId)
                        },
                        MoreAction(Icons.Default.Analytics, stringResource(R.string.more_item_efficiency)) {
                            onNavigateToEfficiency(carId)
                        },
                        MoreAction(Icons.Default.AttachMoney, stringResource(R.string.more_item_cost)) {
                            onNavigateToCost(carId)
                        },
                        MoreAction(Icons.Default.Route, stringResource(R.string.more_item_range)) {
                            onNavigateToRange(carId)
                        },
                        MoreAction(Icons.Default.Bolt, stringResource(R.string.more_item_vampire)) {
                            onNavigateToVampire(carId)
                        },
                        MoreAction(Icons.Default.Timeline, stringResource(R.string.more_item_timeline)) {
                            onNavigateToTimeline(carId)
                        }
                    )
                )
            }

            item { SectionHeader(stringResource(R.string.more_section_reports)) }
            item {
                MoreActionGrid(
                    actions = listOf(
                        MoreAction(Icons.Default.Analytics, stringResource(R.string.more_item_annual_report)) {
                            onNavigateToAnnualReport(carId)
                        },
                        MoreAction(Icons.Default.Update, stringResource(R.string.more_item_export_data)) {
                            onNavigateToExport(carId)
                        },
                    )
                )
            }

            item { SectionHeader(stringResource(R.string.more_section_system)) }
            item {
                SectionCard {
                    MoreRow(
                        icon = Icons.Default.Update,
                        title = stringResource(R.string.more_item_software_updates),
                        onClick = { onNavigateToUpdates(carId) }
                    )
                    MoreDivider()
                    MoreRow(
                        icon = Icons.Default.VerifiedUser,
                        title = stringResource(R.string.more_item_sentry_history),
                        onClick = { onNavigateToSentryHistory(carId) }
                    )
                    MoreDivider()
                    MoreRow(
                        icon = Icons.Default.Settings,
                        title = stringResource(R.string.settings_title),
                        onClick = onNavigateToSettings
                    )
                    MoreDivider()
                    MoreRow(
                        icon = Icons.Default.Info,
                        title = stringResource(R.string.about),
                        onClick = onNavigateToAbout
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    val palette = swissPalette()
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        color = palette.ink,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
private fun MoreActionGrid(actions: List<MoreAction>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        actions.chunked(2).forEach { rowActions ->
            if (rowActions.size == 1) {
                MoreActionTile(
                    action = rowActions.single(),
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    rowActions.forEach { action ->
                        MoreActionTile(
                            action = action,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MoreActionTile(
    action: MoreAction,
    modifier: Modifier = Modifier
) {
    val palette = swissPalette()
    Surface(
        onClick = action.onClick,
        color = MaterialTheme.colorScheme.surface,
        contentColor = palette.ink,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 0.dp,
        shadowElevation = 1.dp,
        modifier = modifier.heightIn(min = 92.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = action.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = palette.ink
            )
        }
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    val palette = swissPalette()
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 0.dp,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column { content() }
    }
}

@Composable
private fun MoreRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    val palette = swissPalette()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.size(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            color = palette.ink,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = palette.muted
        )
    }
}

@Composable
private fun MoreDivider() {
    val palette = swissPalette()
    HorizontalDivider(
        color = palette.outline,
        thickness = 1.dp,
        modifier = Modifier.padding(start = 54.dp)
    )
}

@Preview(showBackground = true, backgroundColor = 0xFFFFFFFF)
@Composable
private fun MoreScreenPreview() {
    MateLinkTheme(darkTheme = false) {
        MoreScreen(
            carId = 1,
            onNavigateToStats = {},
            onNavigateToBattery = {},
            onNavigateToMileage = {},
            onNavigateToDrives = {},
            onNavigateToLongTrips = {},
            onNavigateToUpdates = {},
            onNavigateToSentryHistory = {},
            onNavigateToSettings = {},
            onNavigateToAbout = {},
            onNavigateToEfficiency = {},
            onNavigateToCost = {},
            onNavigateToRange = {},
            onNavigateToVampire = {},
            onNavigateToTimeline = {}
        )
    }
}
