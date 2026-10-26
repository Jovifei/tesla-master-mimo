package com.matelink.ui.screens.trips



import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path

import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

import androidx.hilt.navigation.compose.hiltViewModel
import com.matelink.R
import com.matelink.data.api.models.Units
import com.matelink.domain.analytics.qualifiedChargeMode
import com.matelink.data.local.entity.ChargeSummary
import java.util.Locale
import com.matelink.domain.analytics.toAnalysisDriveData
import com.matelink.domain.model.Trip
import com.matelink.domain.model.UnitFormatter
import com.matelink.domain.analytics.toAnalysisChargeData
import com.matelink.ui.icons.CustomIcons
import com.matelink.ui.components.CostDonutStop
import com.matelink.ui.components.MateLinkLoadingPlaceholder
import com.matelink.ui.components.TripCostDonut
import com.matelink.ui.components.computeCostShades
import com.matelink.ui.components.TripEditActions
import com.matelink.ui.components.TripTimeline
import com.matelink.ui.components.TripTimelineCountry
import com.matelink.ui.components.TripWeatherSparklineCard

import com.matelink.ui.theme.CarColorPalette
import com.matelink.ui.theme.CarColorPalettes
import com.matelink.ui.theme.StatusError

import com.matelink.util.formatDuration
import com.matelink.util.formatMedium
import com.matelink.util.formatMediumNoYear
import com.matelink.ui.components.AmapRouteView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripDetailScreen(
    carId: Int,
    tripStartDate: String,
    exteriorColor: String? = null,
    onNavigateBack: () -> Unit = {},
    onNavigateToDriveDetail: (driveId: Int) -> Unit = {},
    onNavigateToChargeDetail: (chargeId: Int) -> Unit = {},
    onNavigateToCountryStats: (countryCode: String) -> Unit = {},
    savedTripId: Long? = null,
    viewModel: TripDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val isDarkTheme = isSystemInDarkTheme()
    val palette = CarColorPalettes.forExteriorColor(exteriorColor, isDarkTheme)

    LaunchedEffect(carId, tripStartDate, savedTripId) { viewModel.loadTrip(carId, tripStartDate, savedTripId) }

    LaunchedEffect(uiState.justDeleted) {
        if (uiState.justDeleted) onNavigateBack()
    }

    // When the user returns from a child screen (e.g. a drive/charge detail where they may have
    // removed a leg), reload so the trip reflects the current DB state. The ViewModel ignores the
    // first ON_RESUME (on initial composition) by only reloading after at least one ON_PAUSE.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> viewModel.onScreenPaused()
                Lifecycle.Event.ON_RESUME -> viewModel.onScreenResumed()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val displayTitle = run {
        val trip = uiState.trip
        val customName = uiState.savedTripName?.takeIf { it.isNotBlank() }
        when {
            customName != null -> customName
            trip != null -> trip.displayName()
            else -> stringResource(R.string.trip_detail_title)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = displayTitle,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    if (uiState.savedTripId != null) {
                        IconButton(onClick = viewModel::openRenameDialog) {
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = stringResource(R.string.trip_rename_action)
                            )
                        }
                        IconButton(onClick = viewModel::openDeleteConfirm) {
                            Icon(
                                Icons.Filled.DeleteOutline,
                                contentDescription = stringResource(R.string.trip_edit_delete)
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        when {
            uiState.isLoading -> {
                MateLinkLoadingPlaceholder(
                    color = palette.accent,
                    modifier = Modifier.padding(padding)
                )
            }
            uiState.trip == null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) { Text(stringResource(R.string.trip_not_found)) }
            }
            else -> {
                val trip = uiState.trip!!
                Box(modifier = Modifier.padding(padding)) {
                    TripDetailContent(
                        trip = trip,
                        routeSegments = uiState.routeSegments,
                        markers = uiState.markers,
                        isMapLoading = uiState.isMapLoading,
                        routeDeferredByBudget = uiState.routeDeferredByBudget,
                        countries = uiState.countries,
                        units = uiState.units,
                        palette = palette,
                        dcChargeIds = uiState.dcChargeIds,
                        acChargeIds = uiState.acChargeIds,
                        chargeCosts = uiState.chargeCosts,
                        canEdit = uiState.savedTripId != null,
                        weatherPoints = uiState.weatherPoints,
                        onDriveClick = onNavigateToDriveDetail,
                        onChargeClick = onNavigateToChargeDetail,
                        onCountryClick = onNavigateToCountryStats,
                        onAddLeg = viewModel::openAddLegSheet,
                        onMergeTrip = viewModel::openMergeSheet,
                        currencySymbol = uiState.currencySymbol
                    )
                }
            }
        }
    }

    // Sheets and dialogs — rendered at top level so they overlay regardless of scroll position
    if (uiState.showAddLegSheet && uiState.eligibleLegs != null) {
        AddLegSheet(
            eligible = uiState.eligibleLegs!!,
            dcChargeIds = uiState.dcChargeIds,
                        acChargeIds = uiState.acChargeIds,
            palette = palette,
            onPickLegs = viewModel::pickLegs,
            onDismiss = viewModel::closeAddLegSheet
        )
    }
    if (uiState.showMergeSheet) {
        MergeTripSheet(
            adjacentTrips = uiState.adjacentTrips,
            palette = palette,
            onPick = viewModel::pickMergeTarget,
            onDismiss = viewModel::closeMergeSheet
        )
    }
    if (uiState.pendingMergeTarget != null) {
        MergeConfirmDialog(
            onConfirm = viewModel::confirmMergeTarget,
            onDismiss = viewModel::cancelMergeTarget
        )
    }
    if (uiState.showDeleteConfirm) {
        DeleteTripConfirmDialog(
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::closeDeleteConfirm
        )
    }
    if (uiState.showRenameDialog) {
        RenameTripDialog(
            value = uiState.renameDraft,
            onValueChange = viewModel::updateRenameDraft,
            onConfirm = viewModel::confirmRename,
            onDismiss = viewModel::closeRenameDialog
        )
    }
}

@Composable
private fun TripDetailContent(
    trip: Trip,
    routeSegments: List<TripRouteSegment>,
    markers: List<TripMapMarker>,
    isMapLoading: Boolean,
    routeDeferredByBudget: Boolean,
    countries: List<TripCountry>,
    units: Units?,
    palette: CarColorPalette,
    dcChargeIds: Set<Int>,
    acChargeIds: Set<Int>,
    chargeCosts: TripChargeCostProjection?,
    canEdit: Boolean,
    weatherPoints: List<com.matelink.data.repository.TripWeatherPoint>,
    onDriveClick: (driveId: Int) -> Unit,
    onChargeClick: (chargeId: Int) -> Unit,
    onCountryClick: (countryCode: String) -> Unit,
    onAddLeg: () -> Unit,
    onMergeTrip: () -> Unit,
    currencySymbol: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        val dateRangeLabel = remember(trip.startDate, trip.endDate) {
            formatTripDateRange(trip.startDate, trip.endDate)
        }
        val distanceLabel = remember(trip.knownDistanceKm, trip.distanceSampleCount, trip.expectedDriveCount, units) {
            (trip.knownDistanceKm?.let { UnitFormatter.formatDistanceKm(it, units, decimals = 0) } ?: "—") + if(trip.distanceSampleCount < trip.expectedDriveCount) " · ${trip.distanceSampleCount}/${trip.expectedDriveCount}" else ""
        }
        val startCity = remember(trip.startAddress) { extractCity(trip.startAddress) }
        val endCity = remember(trip.endAddress) { extractCity(trip.endAddress) }
        val timelineSegments = remember(trip, dcChargeIds, acChargeIds) { buildTimelineSegments(trip, dcChargeIds, acChargeIds) }
        val timelineCountries = remember(countries) {
            countries.map { TripTimelineCountry(it.countryCode, it.flagEmoji) }
        }
        val totalChargingDurationMin = remember(trip.charges) {
            trip.charges.mapNotNull { it.toAnalysisChargeData().durationMin?.takeIf { v -> v >= 0 } }
                .takeIf { it.isNotEmpty() }?.sumOf { it.toLong() }?.takeIf { it <= Int.MAX_VALUE }?.toInt()
        }

        TripMapCard(
            routeSegments = routeSegments,
            markers = markers,
            isMapLoading = isMapLoading,
            routeDeferredByBudget = routeDeferredByBudget,
            palette = palette,
            dateRangeLabel = dateRangeLabel,
            distanceLabel = distanceLabel,
            onChargeClick = onChargeClick
        )

        TripTimeline(
            segments = timelineSegments,
            startDate = trip.startDate,
            endDate = trip.endDate,
            startCity = startCity,
            endCity = endCity,
            countries = timelineCountries,
            palette = palette,
            totalDurationMin = trip.totalDurationMin,
            totalDrivingDurationMin = trip.knownDrivingDurationMin,
            totalChargingDurationMin = totalChargingDurationMin,
            onCountryClick = onCountryClick
        )

        if (chargeCosts?.totalCost != null) {
            ChargeCostCard(
                trip = trip,
                costs = chargeCosts,
                currencySymbol = currencySymbol,
                palette = palette,
                dcChargeIds = dcChargeIds,
                acChargeIds = acChargeIds,
                units = units,
                onChargeClick = onChargeClick
            )
        }

        BatteryEnergyFlowCard(
            trip = trip,
            dcChargeIds = dcChargeIds,
                acChargeIds = acChargeIds,
            palette = palette
        )

        val legs = remember(trip) { buildLegList(trip) }
        var legsExpanded by rememberSaveable { mutableStateOf(false) }
        val legsChevronRotation by animateFloatAsState(
            targetValue = if (legsExpanded) 90f else 0f,
            animationSpec = tween(durationMillis = 200),
            label = "legsChevron"
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { legsExpanded = !legsExpanded },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.trip_legs),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "(",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${trip.drives.size}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = palette.accent
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = CustomIcons.SteeringWheel,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = palette.accent
                    )
                    if (trip.charges.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "+",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "${trip.charges.size}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = palette.accent
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Filled.ElectricBolt,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = palette.accent
                        )
                    }
                    Text(
                        text = ")",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        modifier = Modifier
                            .size(18.dp)
                            .rotate(legsChevronRotation),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (legsExpanded) {
                    legs.forEach { leg ->
                        when (leg) {
                            is TripLeg.Drive -> DriveLegCard(leg, units, palette) {
                                onDriveClick(leg.drive.driveId)
                            }
                            is TripLeg.Charge -> ChargeLegCard(
                                leg = leg,
                                palette = palette,
                                mode = qualifiedChargeMode(leg.charge.toAnalysisChargeData(), leg.charge.chargeId in dcChargeIds, leg.charge.chargeId in acChargeIds)
                            ) {
                                onChargeClick(leg.charge.chargeId)
                            }
                        }
                    }
                }
            }
        }

        if (canEdit) {
            TripEditActions(
                onAddLeg = onAddLeg,
                onMergeTrip = onMergeTrip
            )
        }

        TripWeatherSparklineCard(
            samples = weatherPoints,
            palette = palette,
            units = units
        )

        Spacer(modifier = Modifier.height(8.dp))
    }
}

// === Charge Cost Card ===

@Composable
private fun ChargeCostCard(
    trip: Trip,
    costs: TripChargeCostProjection,
    currencySymbol: String,
    palette: CarColorPalette,
    dcChargeIds: Set<Int>,
    acChargeIds: Set<Int>,
    units: Units?,
    onChargeClick: (chargeId: Int) -> Unit
) {
    val totalCost = costs.totalCost ?: return
    var expanded by remember { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "chevron"
    )

    val canClassifyEveryStop = remember(costs, dcChargeIds, acChargeIds) {
        costs.pricedCharges.all { item ->
            item.energyKwh != null && qualifiedChargeMode(item.charge.toAnalysisChargeData(), item.charge.chargeId in dcChargeIds, item.charge.chargeId in acChargeIds) != null
        }
    }
    val costStops = remember(costs, dcChargeIds, acChargeIds, canClassifyEveryStop) {
        if (!canClassifyEveryStop) emptyList() else
        costs.pricedCharges
            .filter { it.cost > 0.0 }
            .map { (charge, cost, energyKwh) ->
                CostDonutStop(
                    chargeId = charge.chargeId,
                    label = extractCity(charge.address),
                    cost = cost,
                    durationMin = charge.durationMin,
                    energyAddedKwh = requireNotNull(energyKwh),
                    isDc = qualifiedChargeMode(charge.toAnalysisChargeData(), charge.chargeId in dcChargeIds, charge.chargeId in acChargeIds) == "dc"
                )
            }
    }
    val shades = remember(costStops, palette) { computeCostShades(costStops, palette) }
    val costCharges = costs.pricedCharges

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Filled.ElectricBolt,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.trip_charge_cost),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier
                        .size(18.dp)
                        .rotate(chevronRotation),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (costs.allPriced && costStops.isNotEmpty()) {
                TripCostDonut(
                    stops = costStops,
                    shades = shades,
                    palette = palette,
                    currencySymbol = currencySymbol
                )

            }
            if (!costs.allPriced || costStops.isEmpty()) {
                Text(
                    text = "${stringResource(R.string.cost_recorded_manual_total)}: %.2f %s"
                        .format(totalCost, currencySymbol),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = stringResource(R.string.cost_coverage_counts, costCharges.size,
                    trip.expectedChargeCount, costs.energyCount, costs.pairedCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Distance needs complete price coverage; kWh uses only priced and
            // qualified energy from the same charge, never the raw trip total.
            val distanceUnit = UnitFormatter.getDistanceUnit(units)
            val distanceValue = UnitFormatter.distanceKmDisplayValue(trip.knownDistanceKm ?: 0.0, units)
            val per100 = totalCost.takeIf { costs.allPriced && distanceValue > 0.0 &&
                trip.distanceSampleCount == trip.expectedDriveCount }
                ?.let { it / distanceValue * 100.0 }
            val perKwh = costs.pairedUnitCost
            if (per100 != null || perKwh != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
                ) {
                    per100?.let {
                        EfficiencyPill(
                            value = "%.2f %s".format(it, currencySymbol),
                            unit = "/ 100 $distanceUnit",
                            palette = palette
                        )
                    }
                    perKwh?.let {
                        EfficiencyPill(
                            value = "%.2f %s".format(it, currencySymbol),
                            unit = "/ kWh",
                            palette = palette
                        )
                    }
                }
            }

            if (expanded) {
                costCharges.forEach { (charge, cost, energyKwh) ->
                    val shade = shades.getOrNull(costStops.indexOfFirst { it.chargeId == charge.chargeId })
                        ?: palette.accent
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onChargeClick(charge.chargeId) },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Min),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(4.dp)
                                    .fillMaxHeight()
                                    .background(shade)
                            )
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = extractCity(charge.address),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = energyKwh?.let { "+%.1f kWh · %dm".format(it, charge.durationMin) }
                                            ?: "— kWh · %dm".format(charge.durationMin),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = "%.2f %s".format(cost, currencySymbol),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = shade
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// === Efficiency pill — surfaces €/100km and €/kWh below the cost donut ===

@Composable
private fun EfficiencyPill(
    value: String,
    unit: String,
    palette: CarColorPalette
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(palette.accent.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = palette.accent
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = unit,
            style = MaterialTheme.typography.labelSmall,
            color = palette.accent.copy(alpha = 0.75f)
        )
    }
}

// === Battery energy flow card — horizontal Sankey-style flow (DC+AC in → battery → Used out) ===

@Composable
private fun BatteryEnergyFlowCard(
    trip: Trip,
    dcChargeIds: Set<Int>,
    acChargeIds: Set<Int>,
    palette: CarColorPalette
) {
    fun mode(row: ChargeSummary) = qualifiedChargeMode(row.toAnalysisChargeData(),
        row.chargeId in dcChargeIds, row.chargeId in acChargeIds)
    val dcCharges = trip.charges.filter { mode(it) == "dc" }
    val acCharges = trip.charges.filter { mode(it) == "ac" }
    fun knownEnergy(rows: List<ChargeSummary>): Double? = rows.mapNotNull {
        it.toAnalysisChargeData().batteryInputKwh?.takeIf { v -> v.isFinite() && v >= 0.0 }
    }.takeIf { it.isNotEmpty() }?.sum()?.takeIf(Double::isFinite)
    fun averagePower(rows: List<ChargeSummary>): Double? {
        val pairs = rows.mapNotNull { row ->
            val data = row.toAnalysisChargeData()
            val energy = data.batteryInputKwh?.takeIf { it.isFinite() && it >= 0.0 }
            val minutes = data.durationMin?.takeIf { it > 0 }
            if (energy != null && minutes != null) energy to minutes else null
        }
        return pairs.takeIf { it.isNotEmpty() }?.let {
            (it.sumOf { pair -> pair.first } * 60.0 / it.sumOf { pair -> pair.second.toLong() })
                .takeIf(Double::isFinite)
        }
    }
    val dcKwh = knownEnergy(dcCharges)
    val acKwh = knownEnergy(acCharges)
    val dcAvgKw = averagePower(dcCharges)
    val acAvgKw = averagePower(acCharges)
    val usedKwh = trip.totalEnergyConsumed
    val usedEnergyLabel = stringResource(
        if (trip.energySampleCount == trip.expectedDriveCount) R.string.combined_trip_energy_label
        else R.string.combined_trip_known_energy_label
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.BatteryChargingFull,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.battery),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(12.dp))

            HorizontalEnergyFlow(
                knownDcKwh = dcKwh, dcAvgKw = dcAvgKw,
                knownAcKwh = acKwh, acAvgKw = acAvgKw,
                totalKnownCharged = trip.knownChargeEnergyKwh,
                usedKwh = usedKwh,
                usedEnergyLabel = usedEnergyLabel,
                palette = palette
            )
            Spacer(Modifier.height(8.dp))
            CombinedTripEfficiencySummary(trip)
        }
    }
}

@Composable
private fun HorizontalEnergyFlow(
    knownDcKwh: Double?, dcAvgKw: Double?,
    knownAcKwh: Double?, acAvgKw: Double?,
    totalKnownCharged: Double?,
    usedKwh: Double?,
    usedEnergyLabel: String,
    palette: CarColorPalette
) {
    val dcKwh = knownDcKwh ?: 0.0
    val acKwh = knownAcKwh ?: 0.0
    val totalCharged = dcKwh + acKwh

    // Layout constants (dp). A little taller so labels can sit above/below the thin leads
    // without crowding the battery.
    val cardHeight = 150.dp
    val batteryW = 128.dp
    val batteryH = 72.dp
    val batteryTipW = 7.dp
    val batteryTipH = 26.dp
    // Very thin source thickness — leaves plenty of room for the labels.
    val startT = 6.dp

    // The stream stays at startT for 80% of its run, then expands to its proportional share
    // over the last 20%. Inverse for the outflow (full-width for the first 20%, thin after).
    val flatFraction = 0.80f

    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(cardHeight)
    ) {
        val cardW = maxWidth
        val centerY = cardHeight / 2

        val cardWPx = with(density) { cardW.toPx() }
        val cardHPx = with(density) { cardHeight.toPx() }
        val batteryWPx = with(density) { batteryW.toPx() }
        val batteryHPx = with(density) { batteryH.toPx() }
        val batteryTipWPx = with(density) { batteryTipW.toPx() }
        val batteryTipHPx = with(density) { batteryTipH.toPx() }
        val batteryLeftXPx = (cardWPx - batteryWPx) / 2f
        val batteryRightXPx = batteryLeftXPx + batteryWPx
        val batteryTopYPx = (cardHPx - batteryHPx) / 2f
        val batteryBottomYPx = batteryTopYPx + batteryHPx
        val centerYPx = cardHPx / 2f
        val startTPx = with(density) { startT.toPx() }
        val dcShare = if (totalCharged > 0) dcKwh / totalCharged else 0.0
        val splitYPx = batteryTopYPx + (dcShare * batteryHPx).toFloat()

        val accent = palette.accent
        val acColor = palette.acColor
        val dcColor = palette.dcColor
        val usedColor = StatusError

        Canvas(modifier = Modifier.fillMaxSize()) {
            val cornerPx = 10.dp.toPx()
            val strokePx = 2.5.dp.toPx()

            // x where the inflow transitions from flat-thin to widening
            val inTransitionX = batteryLeftXPx * flatFraction
            // x where the outflow ends its widening (mirror of inTransitionX relative to battery)
            val outRegionWidth = cardWPx - batteryRightXPx
            val outTransitionX = batteryRightXPx + outRegionWidth * (1f - flatFraction)

            // Helper: build a Sankey-style inflow path. Stays at [topY..bottomY] (thickness startT)
            // from x=0 to inTransitionX, then cubic-beziers to (batteryLeftXPx, [batteryTopYAt..
            // batteryBotYAt]).
            fun buildInPath(sourceTopY: Float, sourceBotY: Float, batteryTopYAt: Float, batteryBotYAt: Float): Path {
                val ctrlX = (inTransitionX + batteryLeftXPx) / 2f
                return Path().apply {
                    moveTo(0f, sourceTopY)
                    lineTo(inTransitionX, sourceTopY)
                    cubicTo(
                        ctrlX, sourceTopY,
                        ctrlX, batteryTopYAt,
                        batteryLeftXPx, batteryTopYAt
                    )
                    lineTo(batteryLeftXPx, batteryBotYAt)
                    cubicTo(
                        ctrlX, batteryBotYAt,
                        ctrlX, sourceBotY,
                        inTransitionX, sourceBotY
                    )
                    lineTo(0f, sourceBotY)
                    close()
                }
            }

            // === Inflow streams ===
            when {
                dcKwh > 0 && acKwh > 0 -> {
                    // DC on top: from thin line above center → top portion of battery
                    drawPath(
                        buildInPath(
                            sourceTopY = centerYPx - startTPx,
                            sourceBotY = centerYPx,
                            batteryTopYAt = batteryTopYPx,
                            batteryBotYAt = splitYPx
                        ),
                        brush = Brush.horizontalGradient(
                            colors = listOf(dcColor, dcColor.copy(alpha = 0f)),
                            startX = 0f, endX = batteryLeftXPx
                        )
                    )
                    // AC on bottom
                    drawPath(
                        buildInPath(
                            sourceTopY = centerYPx,
                            sourceBotY = centerYPx + startTPx,
                            batteryTopYAt = splitYPx,
                            batteryBotYAt = batteryBottomYPx
                        ),
                        brush = Brush.horizontalGradient(
                            colors = listOf(acColor, acColor.copy(alpha = 0f)),
                            startX = 0f, endX = batteryLeftXPx
                        )
                    )
                }
                dcKwh > 0 -> {
                    drawPath(
                        buildInPath(
                            sourceTopY = centerYPx - startTPx,
                            sourceBotY = centerYPx + startTPx,
                            batteryTopYAt = batteryTopYPx,
                            batteryBotYAt = batteryBottomYPx
                        ),
                        brush = Brush.horizontalGradient(
                            colors = listOf(dcColor, dcColor.copy(alpha = 0f)),
                            startX = 0f, endX = batteryLeftXPx
                        )
                    )
                }
                acKwh > 0 -> {
                    drawPath(
                        buildInPath(
                            sourceTopY = centerYPx - startTPx,
                            sourceBotY = centerYPx + startTPx,
                            batteryTopYAt = batteryTopYPx,
                            batteryBotYAt = batteryBottomYPx
                        ),
                        brush = Brush.horizontalGradient(
                            colors = listOf(acColor, acColor.copy(alpha = 0f)),
                            startX = 0f, endX = batteryLeftXPx
                        )
                    )
                }
            }

            // === Outflow stream (inverse profile) ===
            // Widens from battery height at batteryRightXPx to thin startT at outTransitionX,
            // then stays thin to cardWPx.
            if (usedKwh != null && usedKwh.isFinite() && usedKwh > 0.0) {
                val ctrlX = (batteryRightXPx + outTransitionX) / 2f
                val outPath = Path().apply {
                    moveTo(batteryRightXPx, batteryTopYPx)
                    cubicTo(
                        ctrlX, batteryTopYPx,
                        ctrlX, centerYPx - startTPx / 2f,
                        outTransitionX, centerYPx - startTPx / 2f
                    )
                    lineTo(cardWPx, centerYPx - startTPx / 2f)
                    lineTo(cardWPx, centerYPx + startTPx / 2f)
                    lineTo(outTransitionX, centerYPx + startTPx / 2f)
                    cubicTo(
                        ctrlX, centerYPx + startTPx / 2f,
                        ctrlX, batteryBottomYPx,
                        batteryRightXPx, batteryBottomYPx
                    )
                    close()
                }
                drawPath(
                    outPath,
                    brush = Brush.horizontalGradient(
                        colors = listOf(usedColor.copy(alpha = 0f), usedColor),
                        startX = batteryRightXPx, endX = cardWPx
                    )
                )
            }

            // === Battery body (drawn on top of the stream edges so seams are clean) ===
            drawRoundRect(
                color = accent.copy(alpha = 0.10f),
                topLeft = Offset(batteryLeftXPx, batteryTopYPx),
                size = Size(batteryWPx, batteryHPx),
                cornerRadius = CornerRadius(cornerPx, cornerPx)
            )
            drawRoundRect(
                color = accent,
                topLeft = Offset(batteryLeftXPx, batteryTopYPx),
                size = Size(batteryWPx, batteryHPx),
                cornerRadius = CornerRadius(cornerPx, cornerPx),
                style = Stroke(width = strokePx)
            )

            // Battery tip (positive terminal) on the right side — makes it unmistakably a battery
            val tipCornerPx = 3.dp.toPx()
            val tipY = batteryTopYPx + (batteryHPx - batteryTipHPx) / 2f
            drawRoundRect(
                color = accent,
                topLeft = Offset(batteryRightXPx, tipY),
                size = Size(batteryTipWPx, batteryTipHPx),
                cornerRadius = CornerRadius(tipCornerPx, tipCornerPx)
            )
        }

        // === Text overlays ===

        // Battery center label: total charged kWh
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (totalKnownCharged != null) {
                Text(
                    text = "%.1f".format(totalKnownCharged),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = accent
                )
                Text(
                    text = stringResource(R.string.kwh_charged),
                    style = MaterialTheme.typography.labelSmall,
                    color = accent.copy(alpha = 0.78f)
                )
            } else {
                Text(
                    text = "—",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }

        // DC label (top-left) — above the thin DC stream
        if (knownDcKwh != null) {
            Column(
                modifier = Modifier.offset(
                    x = 6.dp,
                    y = centerY - startT - 40.dp
                )
            ) {
                Text(
                    text = "%.1f kWh".format(dcKwh),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = dcColor
                )
                Text(
                    text = dcAvgKw?.let { "%.0f kW avg".format(it) } ?: "— kW",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // AC label (bottom-left) — below the thin AC stream
        if (knownAcKwh != null) {
            Column(
                modifier = Modifier.offset(
                    x = 6.dp,
                    y = centerY + startT + 6.dp
                )
            ) {
                Text(
                    text = "%.1f kWh".format(acKwh),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = acColor
                )
                Text(
                    text = "%.1f kW avg".format(acAvgKw),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // OUT label (right) — over the thin outflow section
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(y = -(startT + 20.dp))
                .padding(end = 6.dp),
            horizontalAlignment = Alignment.End
        ) {
            Text(
                text = usedKwh?.takeIf { it.isFinite() }?.let { "${com.matelink.ui.components.formatTelemetryNumber(it, 2)} kWh" }
                    ?: stringResource(R.string.combined_trip_unknown),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = usedColor
            )
            Text(
                text = usedEnergyLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// === Map ===


@Composable
private fun TripMapCard(
    routeSegments: List<TripRouteSegment>,
    markers: List<TripMapMarker>,
    isMapLoading: Boolean,
    routeDeferredByBudget: Boolean,
    palette: CarColorPalette,
    dateRangeLabel: String,
    distanceLabel: String,
    onChargeClick: (chargeId: Int) -> Unit = {}
) {
    if (routeDeferredByBudget) {
        Card(modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(CustomIcons.Road, contentDescription = null, tint = palette.accent,
                    modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(text = "$dateRangeLabel · $distanceLabel",
                        style = MaterialTheme.typography.labelMedium)
                    Text(text = stringResource(R.string.trip_route_deferred_budget),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        return
    }
    // Bridge: Android View click → Compose state → Compose navigation
    var pendingChargeNav by remember { mutableIntStateOf(0) }
    LaunchedEffect(pendingChargeNav) {
        if (pendingChargeNav != 0) {
            onChargeClick(pendingChargeNav)
            pendingChargeNav = 0
        }
    }

    val routeParts = remember(routeSegments) {
        routeSegments.flatMap { seg ->
            seg.parts.map { part -> part.map { Pair(it.latitude, it.longitude) } }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .clip(RoundedCornerShape(12.dp))
        ) {
                AmapRouteView(
                    modifier = Modifier.fillMaxSize(),
                    routeSegments = routeParts,
                    explicitEndpoints = true,
                    startPoint = markers.firstOrNull { it.type == TripMapPointType.START }
                        ?.let { it.latitude to it.longitude },
                    endPoint = markers.firstOrNull { it.type == TripMapPointType.END }
                        ?.let { it.latitude to it.longitude },
                    startTitle = markers.firstOrNull { it.type == TripMapPointType.START }?.label ?: "Start",
                    endTitle = markers.firstOrNull { it.type == TripMapPointType.END }?.label ?: "End"
                )

                MapOverlayChip(
                    text = dateRangeLabel,
                    palette = palette,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                )
                MapOverlayChip(
                    text = distanceLabel,
                    palette = palette,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                )
        }
    }
}

@Composable
private fun MapOverlayChip(
    text: String,
    palette: CarColorPalette,
    modifier: Modifier = Modifier
) {
    val textColor = if (palette.accent.luminance() > 0.5f) Color(0xFF1E2022) else Color.White
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(palette.accent.copy(alpha = 0.85f))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = textColor
        )
    }
}

// === Trip Legs ===

private sealed class TripLeg {
    data class Drive(
        val index: Int,
        val drive: com.matelink.data.local.entity.DriveSummary
    ) : TripLeg()

    data class Charge(
        val index: Int,
        val charge: com.matelink.data.local.entity.ChargeSummary
    ) : TripLeg()
}

private fun buildLegList(trip: Trip): List<TripLeg> {
    val legs = mutableListOf<TripLeg>()
    var driveIdx = 0
    var chargeIdx = 0
    val allEvents = mutableListOf<Pair<String, Any>>()
    trip.drives.forEach { allEvents.add(it.startDate to it) }
    trip.charges.forEach { allEvents.add(it.startDate to it) }
    allEvents.sortBy { it.first }
    for ((_, event) in allEvents) {
        when (event) {
            is com.matelink.data.local.entity.DriveSummary -> {
                driveIdx++
                legs.add(TripLeg.Drive(driveIdx, event))
            }
            is com.matelink.data.local.entity.ChargeSummary -> {
                chargeIdx++
                legs.add(TripLeg.Charge(chargeIdx, event))
            }
        }
    }
    return legs
}

@Composable
private fun DriveLegCard(
    leg: TripLeg.Drive,
    units: Units?,
    palette: CarColorPalette,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                CustomIcons.SteeringWheel,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = palette.accent
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "${extractCity(leg.drive.startAddress)} → ${extractCity(leg.drive.endAddress)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = if (leg.drive.toAnalysisDriveData().distanceKm == null) "—" else "%.1f %s".format(
                        UnitFormatter.distanceKmDisplayValue(leg.drive.toAnalysisDriveData().distanceKm ?: 0.0, units),
                        UnitFormatter.getDistanceUnit(units)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = leg.drive.toAnalysisDriveData().durationMin?.let { formatDuration(LocalContext.current.resources, it) } ?: "—",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ChargeLegCard(
    leg: TripLeg.Charge,
    palette: CarColorPalette,
    mode: String?,
    onClick: () -> Unit
) {
    val chipColor = if (mode == "dc") palette.dcColor else palette.acColor
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = chipColor.copy(alpha = 0.1f)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.ElectricBolt,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = chipColor
            )
            Spacer(modifier = Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(chipColor)
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text(
                    text = mode?.uppercase(Locale.ROOT) ?: stringResource(R.string.cost_mode_unknown),
                    style = MaterialTheme.typography.labelSmall,
                    color = androidx.compose.ui.graphics.Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = extractCity(leg.charge.address),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = leg.charge.toAnalysisChargeData().batteryInputKwh?.let { "+%.1f kWh".format(it) } ?: "— kWh",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = chipColor
                )
                Text(
                    text = leg.charge.toAnalysisChargeData().durationMin?.let { formatDuration(LocalContext.current.resources, it) } ?: "—",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// === Formatting ===

private fun formatTripDateRange(startDate: String, endDate: String): String {
    val start = parseTripDate(startDate) ?: return startDate
    val end = parseTripDate(endDate) ?: return endDate
    val locale = java.util.Locale.getDefault()
    val sameDay = start.toLocalDate() == end.toLocalDate()
    val sameMonth = start.year == end.year && start.month == end.month
    val sameYear = start.year == end.year
    return when {
        sameDay -> start.toLocalDate().formatMediumNoYear(locale)
        sameMonth -> "${start.dayOfMonth} – ${end.toLocalDate().formatMediumNoYear(locale)}"
        sameYear -> "${start.toLocalDate().formatMediumNoYear(locale)} – ${end.toLocalDate().formatMediumNoYear(locale)}"
        else -> "${start.toLocalDate().formatMedium(locale)} – ${end.toLocalDate().formatMedium(locale)}"
    }
}

private fun parseTripDate(value: String): java.time.LocalDateTime? =
    com.matelink.util.parseIsoDateTime(value)
