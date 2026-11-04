package com.matelink.ui.components

import android.animation.ValueAnimator
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalParking
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.matelink.R
import com.matelink.domain.model.visualMotionAllowed
import com.matelink.domain.model.hasVisualRouteEndpoints
import com.matelink.domain.model.ConnectionVisualPhase
import com.matelink.domain.model.VisualWheelReading
import com.matelink.domain.model.VisualWheelState
import com.matelink.data.local.TirePosition
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** No network source and no private photo lookup: this is a public, bundled vehicle illustration. */
@Composable
fun BundledVehicleIllustration(modifier: Modifier = Modifier) {
    AsyncImage(model = "file:///android_asset/car_images/myj_PPSW_WY19P.png",
        contentDescription = stringResource(R.string.interaction_vehicle_illustration),
        contentScale = ContentScale.Fit, modifier = modifier)
}

@Composable
fun rememberVisualMotionEnabled(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var enabled by remember(lifecycle) { mutableStateOf(
        visualMotionAllowed(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED), ValueAnimator.areAnimatorsEnabled(), true)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> enabled =
            visualMotionAllowed(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED), ValueAnimator.areAnimatorsEnabled(), true) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return enabled
}

@Composable
fun ConnectionVisual(phase: ConnectionVisualPhase, vehicle: Boolean = false,
    modifier: Modifier = Modifier) {
    val accent = if (phase == ConnectionVisualPhase.UNAVAILABLE) MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.primary
    val label = stringResource(when (phase) {
        ConnectionVisualPhase.IDLE -> R.string.interaction_link_idle
        ConnectionVisualPhase.CONNECTING -> R.string.interaction_link_pending
        ConnectionVisualPhase.ACTION_REQUIRED -> R.string.interaction_link_action
        ConnectionVisualPhase.CONNECTED -> R.string.interaction_link_confirmed
        ConnectionVisualPhase.UNAVAILABLE -> R.string.interaction_link_unavailable
    })
    Column(modifier.fillMaxWidth().testTag("interaction-connection-${phase.name}"),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth().height(86.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center) {
            if (vehicle) BundledVehicleIllustration(Modifier.width(132.dp).height(80.dp))
            else Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Lock, null, tint = accent)
                    Text("Tesla", style = MaterialTheme.typography.labelMedium)
                }
            }
            Box(Modifier.width(72.dp).height(48.dp), contentAlignment = Alignment.Center) {
                if (phase == ConnectionVisualPhase.CONNECTING) {
                    // Native progress obeys system duration scale; no fake completion value.
                    if (rememberVisualMotionEnabled()) LinearProgressIndicator(
                        modifier = Modifier.width(50.dp).height(3.dp), color = accent)
                    else Icon(Icons.Default.Link, null, tint = accent)
                } else Icon(if (phase == ConnectionVisualPhase.CONNECTED) Icons.Default.CheckCircle
                    else if (phase == ConnectionVisualPhase.UNAVAILABLE) Icons.Default.CloudOff
                    else Icons.Default.Link, null, tint = accent)
            }
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.AccountCircle, null, tint = accent)
                    Text("MateLink", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Text(label, style = MaterialTheme.typography.bodySmall, color = accent)
    }
}

/** The drawing expresses endpoint order, never geographic shape or observed vehicle movement. */
@Composable
fun RouteInteractionVisual(start: String?, end: String?, modifier: Modifier = Modifier) {
    var selected by remember(start, end) { mutableStateOf(0) }
    val knownStart = !start.isNullOrBlank(); val knownEnd = !end.isNullOrBlank()
    val accent = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.outline
    val description = stringResource(R.string.interaction_route_schematic)
    Column(modifier.fillMaxWidth().testTag("interaction-route")) {
        Canvas(Modifier.fillMaxWidth().height(42.dp).semantics { contentDescription = description }) {
            val from = Offset(18.dp.toPx(), size.height * .58f)
            val to = Offset(size.width - 18.dp.toPx(), size.height * .42f)
            val path = Path().apply { moveTo(from.x, from.y)
                cubicTo(size.width * .36f, 0f, size.width * .60f, size.height, to.x, to.y) }
            drawPath(path, if (hasVisualRouteEndpoints(start, end)) accent else muted,
                style = Stroke(2.dp.toPx(), cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))))
            drawCircle(if (knownStart) accent else muted, 6.dp.toPx(), from)
            drawCircle(if (knownEnd) accent else muted, 6.dp.toPx(), to)
        }
        listOf(start, end).forEachIndexed { index, address ->
            val label = stringResource(if (index == 0) R.string.start else R.string.end)
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .testTag("interaction-route-$index")
                .selectable(selected == index, role = Role.Button, onClick = { selected = index })
                .background(if (selected == index) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .42f)
                    else MaterialTheme.colorScheme.surface, RoundedCornerShape(10.dp)).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (index == 0) "A" else "B", fontWeight = FontWeight.Bold, color = accent)
                Column(Modifier.weight(1f)) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(address?.takeIf { it.isNotBlank() } ?: stringResource(R.string.unknown_location),
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Text(description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
fun VehiclePositionSchematic(modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.interaction_vehicle_top),
        contentDescription = stringResource(R.string.interaction_vehicle_schematic),
        contentScale = ContentScale.Fit, modifier = modifier)
}

@Composable
fun wheelVisualLabel(wheel: TirePosition): String = stringResource(when (wheel) {
    TirePosition.FL -> R.string.tire_fl_full
    TirePosition.FR -> R.string.tire_fr_full
    TirePosition.RL -> R.string.tire_rl_full
    TirePosition.RR -> R.string.tire_rr_full
})

@Composable
fun TpmsVehicleVisual(readings: Map<TirePosition, VisualWheelReading>, selected: TirePosition?,
    onSelect: (TirePosition?) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().testTag("interaction-tpms")) {
        Box(Modifier.fillMaxWidth().heightIn(min = 216.dp), contentAlignment = Alignment.Center) {
            VehiclePositionSchematic(Modifier.height(204.dp).width(108.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(72.dp)) {
                listOf(listOf(TirePosition.FL, TirePosition.FR), listOf(TirePosition.RL, TirePosition.RR)).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        pair.forEach { wheel ->
                            val reading = readings[wheel]
                            val value = reading?.pressureBar?.let { String.format(Locale.getDefault(), "%.2f bar", it) }
                                ?: stringResource(R.string.not_available)
                            val label = wheelVisualLabel(wheel)
                            val stateLabel = stringResource(when (reading?.state) {
                                VisualWheelState.RECORDED -> R.string.interaction_observed
                                VisualWheelState.STALE -> R.string.interaction_stale
                                VisualWheelState.WARNING -> R.string.interaction_warning
                                else -> R.string.interaction_unknown
                            })
                            val isSelected = selected == wheel
                            val accent = if (reading?.state == VisualWheelState.WARNING) MaterialTheme.colorScheme.error
                                else if (reading?.state == VisualWheelState.RECORDED) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            Column(Modifier.widthIn(max = 112.dp).heightIn(min = 56.dp)
                                .testTag("interaction-wheel-${wheel.name}")
                                .selectable(isSelected, role = Role.Button, onClick = { onSelect(if (isSelected) null else wheel) })
                                .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp)).padding(8.dp)) {
                                Text(label, style = MaterialTheme.typography.labelSmall)
                                Text(value, style = MaterialTheme.typography.titleSmall, color = accent)
                                Text(stateLabel, style = MaterialTheme.typography.labelSmall, color = accent)
                            }
                        }
                    }
                }
            }
        }
        val reading = selected?.let { readings[it] }
        Text(if (selected == null) stringResource(R.string.interaction_wheel_select)
            else wheelVisualLabel(selected) + " · " + (reading?.observedAt?.let {
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
            } ?: stringResource(R.string.interaction_unknown)),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.interaction_vehicle_schematic), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Category-level flags deliberately never identify a particular door/window. */
@Composable
fun VehicleOpeningsVisual(doors: Boolean?, windows: Boolean?, frunk: Boolean?, trunk: Boolean?,
    modifier: Modifier = Modifier) {
    val warning = MaterialTheme.colorScheme.error
    val muted = MaterialTheme.colorScheme.outline
    Column(modifier.fillMaxWidth().testTag("interaction-openings"), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.width(140.dp).height(184.dp), contentAlignment = Alignment.Center) {
            VehiclePositionSchematic(Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize()) {
                if (doors == true) {
                    drawLine(warning, Offset(size.width * .17f, size.height * .36f), Offset(size.width * .17f, size.height * .61f), 4.dp.toPx(), StrokeCap.Round)
                    drawLine(warning, Offset(size.width * .83f, size.height * .36f), Offset(size.width * .83f, size.height * .61f), 4.dp.toPx(), StrokeCap.Round)
                }
                if (windows == true) drawRoundRect(warning.copy(alpha = .24f), Offset(size.width * .35f, size.height * .28f),
                    Size(size.width * .3f, size.height * .38f))
                if (frunk == true) drawLine(warning, Offset(size.width * .32f, size.height * .15f), Offset(size.width * .68f, size.height * .15f), 4.dp.toPx(), StrokeCap.Round)
                if (trunk == true) drawLine(warning, Offset(size.width * .32f, size.height * .88f), Offset(size.width * .68f, size.height * .88f), 4.dp.toPx(), StrokeCap.Round)
            }
        }
        Text(stringResource(R.string.interaction_openings_aggregate), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun EmptyHistoryVisual(charging: Boolean, failed: Boolean = false, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Box(modifier.width(142.dp).height(76.dp).testTag("interaction-empty-${if (charging) "charge" else "drive"}"),
        contentAlignment = Alignment.Center) {
        if (failed) Icon(Icons.Default.CloudOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(42.dp))
        else if (charging) {
            BundledVehicleIllustration(Modifier.fillMaxSize().padding(end = 24.dp))
            Icon(Icons.Default.Bolt, null, tint = accent, modifier = Modifier.align(Alignment.CenterEnd).size(32.dp))
        } else Canvas(Modifier.fillMaxSize()) {
            val path = Path().apply { moveTo(size.width * .16f, size.height * .66f)
                cubicTo(size.width * .3f, size.height * .05f, size.width * .64f, size.height, size.width * .84f, size.height * .28f) }
            drawPath(path, accent.copy(alpha = .65f), style = Stroke(2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))))
            drawCircle(accent, 6.dp.toPx(), Offset(size.width * .16f, size.height * .66f))
            drawCircle(accent.copy(alpha = .5f), 6.dp.toPx(), Offset(size.width * .84f, size.height * .28f))
        }
    }
}

/** Two nodes describe only a recorded charging interval, never an invented non-charging event. */
@Composable
fun RecordedChargeRail(hasRecordedEnd: Boolean, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier.width(18.dp).height(56.dp).testTag("interaction-recorded-interval")) {
        val x = size.width / 2
        drawLine(accent.copy(alpha = .35f), Offset(x, 6.dp.toPx()), Offset(x, size.height - 6.dp.toPx()), 2.dp.toPx())
        drawCircle(accent, 4.dp.toPx(), Offset(x, 6.dp.toPx()))
        drawCircle(accent, 4.dp.toPx(), Offset(x, size.height - 6.dp.toPx()),
            style = if (hasRecordedEnd) Stroke(2.dp.toPx()) else Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f,2f))))
    }
}
