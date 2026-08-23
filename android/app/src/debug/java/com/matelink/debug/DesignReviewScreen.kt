package com.matelink.debug

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.matelink.ui.components.MetricPanelKind
import com.matelink.ui.components.MetricStatusPanel
import com.matelink.ui.components.MetricValueCard
import com.matelink.ui.theme.Typography

private enum class DesignDirection { PRECISION_TELEMETRY, PURE_MINIMAL }

private enum class ReviewPage {
    VEHICLE,
    TRIPS,
    ENERGY,
    ANALYSIS,
    COLLECTING,
    UNAVAILABLE
}

private data class ReviewCopy(
    val reviewTitle: String,
    val precision: String,
    val minimal: String,
    val light: String,
    val dark: String,
    val vehicle: String,
    val trips: String,
    val energy: String,
    val analysis: String,
    val collecting: String,
    val unavailable: String,
    val vehicleName: String,
    val online: String,
    val updated: String,
    val range: String,
    val battery: String,
    val driveToday: String,
    val energyFlow: String,
    val driving: String,
    val charged: String,
    val loss: String,
    val cost: String,
    val conclusion: String,
    val evidence: String,
    val sample: String,
    val collectingTitle: String,
    val collectingBody: String,
    val unavailableTitle: String,
    val unavailableBody: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesignReviewScreen() {
    var directionName by rememberSaveable { mutableStateOf(DesignDirection.PRECISION_TELEMETRY.name) }
    var pageName by rememberSaveable { mutableStateOf(ReviewPage.VEHICLE.name) }
    var darkMode by rememberSaveable { mutableStateOf(false) }
    val direction = DesignDirection.valueOf(directionName)
    val page = ReviewPage.valueOf(pageName)
    val chinese = LocalConfiguration.current.locales[0]?.language == "zh"
    val copy = reviewCopy(chinese)

    MaterialTheme(
        colorScheme = reviewColors(direction, darkMode),
        typography = Typography
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(copy.reviewTitle, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = if (direction == DesignDirection.PRECISION_TELEMETRY) copy.precision else copy.minimal,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { darkMode = !darkMode }) {
                            Icon(
                                imageVector = if (darkMode) Icons.Default.Bolt else Icons.Default.Refresh,
                                contentDescription = if (darkMode) copy.dark else copy.light
                            )
                        }
                    },
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
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    ReviewControlRow(
                        copy = copy,
                        direction = direction,
                        darkMode = darkMode,
                        onDirectionSelected = { directionName = it.name },
                        onDarkModeToggled = { darkMode = it }
                    )
                }
                item {
                    PageSelector(
                        copy = copy,
                        page = page,
                        onPageSelected = { pageName = it.name }
                    )
                }
                item {
                    ReviewPageContent(page = page, copy = copy, direction = direction)
                }
            }
        }
    }
}

@Composable
private fun ReviewControlRow(
    copy: ReviewCopy,
    direction: DesignDirection,
    darkMode: Boolean,
    onDirectionSelected: (DesignDirection) -> Unit,
    onDarkModeToggled: (Boolean) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = direction == DesignDirection.PRECISION_TELEMETRY,
                onClick = { onDirectionSelected(DesignDirection.PRECISION_TELEMETRY) },
                label = { Text(copy.precision) }
            )
            FilterChip(
                selected = direction == DesignDirection.PURE_MINIMAL,
                onClick = { onDirectionSelected(DesignDirection.PURE_MINIMAL) },
                label = { Text(copy.minimal) }
            )
            FilterChip(
                selected = darkMode,
                onClick = { onDarkModeToggled(!darkMode) },
                label = { Text(if (darkMode) copy.dark else copy.light) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            )
        }
        Text(
            text = "DEBUG ONLY · not included in Release",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PageSelector(
    copy: ReviewCopy,
    page: ReviewPage,
    onPageSelected: (ReviewPage) -> Unit
) {
    val labels = listOf(
        ReviewPage.VEHICLE to copy.vehicle,
        ReviewPage.TRIPS to copy.trips,
        ReviewPage.ENERGY to copy.energy,
        ReviewPage.ANALYSIS to copy.analysis,
        ReviewPage.COLLECTING to copy.collecting,
        ReviewPage.UNAVAILABLE to copy.unavailable
    )
    Row(
        modifier = Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        labels.forEach { (candidate, label) ->
            FilterChip(
                selected = candidate == page,
                onClick = { onPageSelected(candidate) },
                label = { Text(label) }
            )
        }
    }
}

@Composable
private fun ReviewPageContent(
    page: ReviewPage,
    copy: ReviewCopy,
    direction: DesignDirection
) {
    when (page) {
        ReviewPage.VEHICLE -> VehicleSample(copy, direction)
        ReviewPage.TRIPS -> TripsSample(copy)
        ReviewPage.ENERGY -> EnergySample(copy)
        ReviewPage.ANALYSIS -> AnalysisSample(copy)
        ReviewPage.COLLECTING -> MetricStatusPanel(
            kind = MetricPanelKind.COLLECTING,
            title = copy.collectingTitle,
            body = copy.collectingBody
        )
        ReviewPage.UNAVAILABLE -> MetricStatusPanel(
            kind = MetricPanelKind.UNAVAILABLE,
            title = copy.unavailableTitle,
            body = copy.unavailableBody
        )
    }
}

@Composable
private fun VehicleSample(copy: ReviewCopy, direction: DesignDirection) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (direction == DesignDirection.PRECISION_TELEMETRY) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                }
            )
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DirectionsCar, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = copy.vehicleName,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(18.dp))
                Text("76%", style = MaterialTheme.typography.displayLarge)
                AssistChip(
                    onClick = {},
                    label = { Text(copy.online) },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        labelColor = MaterialTheme.colorScheme.onSurface
                    )
                )
                Text(
                    text = copy.updated,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricValueCard(copy.range, "398 km", Modifier.weight(1f))
            MetricValueCard(copy.battery, "76%", Modifier.weight(1f))
            MetricValueCard(copy.driveToday, "42 km", Modifier.weight(1f))
        }
    }
}

@Composable
private fun TripsSample(copy: ReviewCopy) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MetricValueCard(
            label = copy.trips,
            value = "42 km · 0:54",
            supporting = copy.sample + ": 1 trip · " + copy.updated
        )
        listOf("08:12  ·  13.2 km  ·  151 Wh/km", "14:05  ·  28.8 km  ·  163 Wh/km").forEach {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Icon(Icons.Default.Route, null, tint = MaterialTheme.colorScheme.primary)
                    Text(it, modifier = Modifier.padding(start = 12.dp).weight(1f))
                }
            }
        }
    }
}

@Composable
private fun EnergySample(copy: ReviewCopy) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(copy.energyFlow, style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricValueCard(copy.driving, "6.7 kWh", Modifier.weight(1f))
            MetricValueCard(copy.charged, "9.1 kWh", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricValueCard(copy.loss, "0.6 kWh", Modifier.weight(1f))
            MetricValueCard(copy.cost, "¥8.40", Modifier.weight(1f))
        }
        Text(
            text = copy.evidence + ": TeslaMate · " + copy.sample + ": 3",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AnalysisSample(copy: ReviewCopy) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(copy.analysis, style = MaterialTheme.typography.titleLarge)
        listOf(
            "153 Wh/km" to (copy.evidence + ": " + copy.sample + " 14 · 427 km"),
            "−8%" to (copy.evidence + ": 15–25°C baseline"),
            "¥42 / month" to (copy.evidence + ": charging records only")
        ).forEach { (value, supporting) ->
            MetricValueCard(copy.conclusion, value, supporting = supporting)
        }
    }
}

private fun reviewColors(direction: DesignDirection, dark: Boolean) = when (direction) {
    DesignDirection.PRECISION_TELEMETRY -> if (dark) {
        darkColorScheme(
            primary = Color(0xFF67E8F9),
            onPrimary = Color(0xFF083344),
            primaryContainer = Color(0xFF164E63),
            onPrimaryContainer = Color(0xFFCFFAFE),
            secondary = Color(0xFFCDD2D8),
            onSecondary = Color(0xFF1A1D22),
            secondaryContainer = Color(0xFF2B3038),
            onSecondaryContainer = Color(0xFFE6E9ED),
            background = Color(0xFF111315),
            surface = Color(0xFF15181C),
            onSurface = Color(0xFFF5F6F7),
            onSurfaceVariant = Color(0xFFB7BDC6),
            surfaceContainerLow = Color(0xFF191D22),
            surfaceContainerHighest = Color(0xFF282D34),
            outline = Color(0xFF505862)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF0E7490),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFCFFAFE),
            onPrimaryContainer = Color(0xFF164E63),
            secondary = Color(0xFF374151),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFE2E8F0),
            onSecondaryContainer = Color(0xFF1F2937),
            background = Color(0xFFF8FAFC),
            surface = Color.White,
            onSurface = Color(0xFF111827),
            onSurfaceVariant = Color(0xFF4B5563),
            surfaceContainerLow = Color(0xFFF1F5F9),
            surfaceContainerHighest = Color(0xFFE2E8F0),
            outline = Color(0xFF94A3B8)
        )
    }
    DesignDirection.PURE_MINIMAL -> if (dark) {
        darkColorScheme(
            primary = Color(0xFFF5F5F5),
            onPrimary = Color(0xFF121212),
            primaryContainer = Color(0xFF303030),
            onPrimaryContainer = Color.White,
            secondary = Color(0xFFD4D4D4),
            onSecondary = Color(0xFF171717),
            secondaryContainer = Color(0xFF303030),
            onSecondaryContainer = Color(0xFFF5F5F5),
            background = Color(0xFF0B0B0B),
            surface = Color(0xFF141414),
            onSurface = Color(0xFFF5F5F5),
            onSurfaceVariant = Color(0xFFA3A3A3),
            surfaceContainerLow = Color(0xFF1A1A1A),
            surfaceContainerHighest = Color(0xFF303030),
            outline = Color(0xFF737373)
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF171717),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFEAEAEA),
            onPrimaryContainer = Color(0xFF171717),
            secondary = Color(0xFF404040),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFEAEAEA),
            onSecondaryContainer = Color(0xFF171717),
            background = Color(0xFFFAFAFA),
            surface = Color.White,
            onSurface = Color(0xFF171717),
            onSurfaceVariant = Color(0xFF525252),
            surfaceContainerLow = Color(0xFFF4F4F4),
            surfaceContainerHighest = Color(0xFFEAEAEA),
            outline = Color(0xFFA3A3A3)
        )
    }
}

private fun reviewCopy(chinese: Boolean): ReviewCopy = if (chinese) {
    ReviewCopy(
        reviewTitle = "MateLink 设计审查",
        precision = "精密遥测",
        minimal = "纯粹极简",
        light = "浅色",
        dark = "深色",
        vehicle = "车辆",
        trips = "行程",
        energy = "能源",
        analysis = "分析",
        collecting = "采集中",
        unavailable = "不可用",
        vehicleName = "Development Model 3 长续航版",
        online = "在线",
        updated = "4 分钟前更新",
        range = "官方续航",
        battery = "电量",
        driveToday = "今日行驶",
        energyFlow = "今天的能量流",
        driving = "驾驶消耗",
        charged = "充入电量",
        loss = "充电损耗",
        cost = "充电成本",
        conclusion = "可验证结论",
        evidence = "依据",
        sample = "样本",
        collectingTitle = "正在建立你的车辆基线",
        collectingBody = "我们会从连接之日起记录数据。数据不足时不会用 0 或估算值代替真实结果。",
        unavailableTitle = "此指标暂不可用",
        unavailableBody = "当前数据源不提供所需字段。车辆状态和其他功能仍可继续使用。"
    )
} else {
    ReviewCopy(
        reviewTitle = "MateLink design review",
        precision = "Precision telemetry",
        minimal = "Pure minimal",
        light = "Light",
        dark = "Dark",
        vehicle = "Vehicle",
        trips = "Trips",
        energy = "Energy",
        analysis = "Analysis",
        collecting = "Collecting",
        unavailable = "Unavailable",
        vehicleName = "Development Model 3 Long Range",
        online = "Online",
        updated = "Updated 4 min ago",
        range = "Official range",
        battery = "Battery",
        driveToday = "Today",
        energyFlow = "Today's energy flow",
        driving = "Driving used",
        charged = "Added to battery",
        loss = "Charge loss",
        cost = "Charge cost",
        conclusion = "Verified conclusion",
        evidence = "Evidence",
        sample = "Sample",
        collectingTitle = "Building your vehicle baseline",
        collectingBody = "Data begins on the connection date. Missing history is never shown as zero or as a made-up estimate.",
        unavailableTitle = "This metric is unavailable",
        unavailableBody = "The active source does not provide the required data. Vehicle status and other features remain available."
    )
}
