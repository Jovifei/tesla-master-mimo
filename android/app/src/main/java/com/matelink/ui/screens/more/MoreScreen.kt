package com.matelink.ui.screens.more

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import java.util.Locale

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
    onNavigateToTrips: (carId: Int) -> Unit,
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
    onNavigateToExport: (carId: Int) -> Unit = {},
    onNavigateToVehicle3d: (carId: Int) -> Unit = {},
    onNavigateToCurrentCharge: (carId: Int) -> Unit = {}
) {
    val palette = swissPalette()
    Scaffold(
        containerColor = palette.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.nav_more), fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.surface)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item {
                SectionCard {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                        Text(
                            text = stringResource(R.string.more_status_title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = palette.ink
                        )
                        Text(
                            text = stringResource(R.string.more_status_body),
                            style = MaterialTheme.typography.bodyMedium,
                            color = palette.muted,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.more_section_analysis)) }
            item {
                SectionCard {
                    MoreRow(
                        icon = Icons.Default.Speed,
                        title = stringResource(R.string.more_item_statistics),
                        onClick = { onNavigateToStats(carId) }
                    )
                    MoreDivider()
                    MoreRow(
                        icon = Icons.Default.BatteryStd,
                        title = stringResource(R.string.more_item_battery_health),
                        onClick = { onNavigateToBattery(carId) }
                    )
                    MoreDivider()
                    MoreRow(
                        icon = Icons.Default.Map,
                        title = stringResource(R.string.more_item_mileage),
                        onClick = { onNavigateToMileage(carId) }
                    )
                    MoreDivider()
                    MoreRow(
                        icon = Icons.Default.History,
                        title = stringResource(R.string.more_item_trips),
                        onClick = { onNavigateToTrips(carId) }
                    )
                    MoreDivider()
                    MoreRow(
                        icon = Icons.Default.Analytics,
                        title = stringResource(R.string.more_item_efficiency),
                        onClick = { onNavigateToEfficiency(carId) }
                    )
                    MoreDivider()
                    MoreRow(
                        icon = Icons.Default.AttachMoney,
                        title = stringResource(R.string.more_item_cost),
                        onClick = { onNavigateToCost(carId) }
                    )
                    MoreDivider()
                    MoreRow(
                        icon = Icons.Default.Route,
                        title = stringResource(R.string.more_item_range),
                        onClick = { onNavigateToRange(carId) }
                    )
                    MoreDivider()
                    MoreRow(
                        icon = Icons.Default.Bolt,
                        title = stringResource(R.string.more_item_vampire),
                        onClick = { onNavigateToVampire(carId) }
                    )
                    MoreDivider()
                    MoreRow(
                        icon = Icons.Default.Timeline,
                        title = stringResource(R.string.more_item_timeline),
                        onClick = { onNavigateToTimeline(carId) }
                    )
                }
            }

            item { SectionHeader(stringResource(R.string.more_section_reports)) }
            item {
                SectionCard {
                    MoreRow(
                        icon = Icons.Default.Analytics,
                        title = stringResource(R.string.more_item_annual_report),
                        onClick = { onNavigateToAnnualReport(carId) }
                    )
                    MoreDivider()
                    MoreRow(
                        icon = Icons.Default.Update,
                        title = stringResource(R.string.more_item_export_data),
                        onClick = { onNavigateToExport(carId) }
                    )
                    MoreDivider()
                    MoreRow(
                        icon = Icons.Default.VerifiedUser,
                        title = stringResource(R.string.more_item_vehicle_3d_preview),
                        onClick = { onNavigateToVehicle3d(carId) }
                    )
                    MoreDivider()
                    MoreRow(
                        icon = Icons.Default.Bolt,
                        title = stringResource(R.string.current_charge),
                        onClick = { onNavigateToCurrentCharge(carId) }
                    )
                }
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
        text = label.uppercase(Locale.ROOT),
        style = MaterialTheme.typography.labelMedium,
        color = palette.muted,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 4.dp)
    )
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    val palette = swissPalette()
    Surface(
        color = palette.surface,
        shape = RoundedCornerShape(8.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, palette.outline),
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
            tint = palette.ink,
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
            onNavigateToTrips = {},
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
