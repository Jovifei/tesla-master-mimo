package com.matelink.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.matelink.domain.model.VehicleHeroModel
import com.matelink.domain.model.resolveVehicleHeroProfile
import com.matelink.ui.theme.MetricMono

@Immutable
data class TelemetryMetricSpec(
    val icon: ImageVector,
    val label: String,
    val value: String,
    val tint: Color
)

@Composable
fun TelemetryPanel(
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
        shadowElevation = 1.dp,
        border = BorderStroke(
            0.5.dp,
            MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.48f)
        )
    ) {
        content()
    }
}

@Composable
fun TelemetrySectionHeader(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        trailing?.invoke()
    }
}

@Composable
fun TelemetryMetricStrip(
    metrics: List<TelemetryMetricSpec>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        metrics.forEachIndexed { index, metric ->
            TelemetryMetricCell(
                metric = metric,
                modifier = Modifier.weight(1f)
            )
            if (index != metrics.lastIndex) {
                Box(
                    modifier = Modifier
                        .height(64.dp)
                        .width(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                )
            }
        }
    }
}

@Composable
private fun TelemetryMetricCell(
    metric: TelemetryMetricSpec,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(
            imageVector = metric.icon,
            contentDescription = null,
            tint = metric.tint,
            modifier = Modifier.size(19.dp)
        )
        Text(
            text = metric.value,
            style = MaterialTheme.typography.titleSmall.copy(fontFamily = MetricMono),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
        Text(
            text = metric.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun TelemetryGauge(
    progress: Float,
    headline: String,
    supporting: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        val track = MaterialTheme.colorScheme.surfaceContainerHighest
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * 0.085f
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = track,
                startAngle = 135f,
                sweepAngle = 270f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            drawArc(
                brush = Brush.sweepGradient(
                    listOf(color.copy(alpha = 0.62f), color, color.copy(alpha = 0.88f))
                ),
                startAngle = 135f,
                sweepAngle = 270f * progress.coerceIn(0f, 1f),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = headline,
                style = MaterialTheme.typography.displaySmall.copy(fontFamily = MetricMono),
                fontWeight = FontWeight.Bold
            )
            Text(
                text = supporting,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun VehicleHeroGraphic(
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    model: String? = null,
    exteriorColor: String? = null,
    wheelType: String? = null,
    trimBadging: String? = null
) {
    val profile = remember(model, exteriorColor, wheelType, trimBadging) {
        resolveVehicleHeroProfile(model, exteriorColor, wheelType, trimBadging)
    }
    val body = heroPaintColor(profile.colorCode, accent)
    val window = MaterialTheme.colorScheme.surfaceContainerHighest
    val outline = MaterialTheme.colorScheme.outlineVariant
    val wheel = MaterialTheme.colorScheme.surface
    Canvas(modifier = modifier.fillMaxWidth().height(132.dp)) {
        val w = size.width
        val h = size.height

        drawOval(
            brush = Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.22f), Color.Transparent),
                center = Offset(w * 0.56f, h * 0.72f),
                radius = w * 0.42f
            ),
            topLeft = Offset(w * 0.18f, h * 0.48f),
            size = Size(w * 0.68f, h * 0.48f)
        )

        val car = vehicleHeroBody(profile.model, w, h)
        drawPath(
            path = car,
            brush = Brush.linearGradient(
                colors = listOf(body, body.copy(alpha = 0.72f), body),
                start = Offset(w * 0.18f, h * 0.38f),
                end = Offset(w * 0.86f, h * 0.74f)
            )
        )
        drawPath(
            path = car,
            color = outline,
            style = Stroke(width = 1.dp.toPx())
        )

        val glass = Path().apply {
            val roofHeight = when (profile.model) {
                VehicleHeroModel.MODEL_Y, VehicleHeroModel.MODEL_X -> 0.25f
                VehicleHeroModel.MODEL_S -> 0.32f
                else -> 0.30f
            }
            moveTo(w * 0.40f, h * 0.46f)
            cubicTo(w * 0.48f, h * roofHeight, w * 0.59f, h * (roofHeight - 0.01f), w * 0.69f, h * (roofHeight + 0.07f))
            lineTo(w * 0.76f, h * 0.49f)
            close()
        }
        drawPath(glass, color = window)
        drawLine(
            color = outline,
            start = Offset(w * 0.59f, h * 0.31f),
            end = Offset(w * 0.59f, h * 0.51f),
            strokeWidth = 1.dp.toPx()
        )

        val wheelRadius = h * (0.115f + ((profile.wheelDiameterInches ?: 19) - 18) * 0.006f)
        val wheelXs = when (profile.model) {
            VehicleHeroModel.MODEL_S -> listOf(w * 0.27f, w * 0.78f)
            VehicleHeroModel.MODEL_X -> listOf(w * 0.29f, w * 0.77f)
            else -> listOf(w * 0.30f, w * 0.76f)
        }
        wheelXs.forEach { wheelX ->
            drawCircle(color = wheel, radius = wheelRadius, center = Offset(wheelX, h * 0.72f))
            drawCircle(color = outline, radius = wheelRadius * 0.56f, center = Offset(wheelX, h * 0.72f))
            drawCircle(
                color = if (profile.isPerformance) Color(0xFFE45757) else accent,
                radius = wheelRadius * 0.20f,
                center = Offset(wheelX, h * 0.72f)
            )
        }

        drawRoundRect(
            color = accent,
            topLeft = Offset(w * 0.845f, h * 0.56f),
            size = Size(w * 0.045f, h * 0.055f),
            cornerRadius = CornerRadius(4.dp.toPx())
        )
    }
}

private fun vehicleHeroBody(model: VehicleHeroModel, width: Float, height: Float): Path = Path().apply {
    when (model) {
        VehicleHeroModel.MODEL_Y -> {
            moveTo(width * 0.12f, height * 0.69f)
            cubicTo(width * 0.18f, height * 0.49f, width * 0.30f, height * 0.44f, width * 0.40f, height * 0.41f)
            cubicTo(width * 0.46f, height * 0.24f, width * 0.58f, height * 0.20f, width * 0.70f, height * 0.29f)
            cubicTo(width * 0.77f, height * 0.34f, width * 0.81f, height * 0.48f, width * 0.89f, height * 0.53f)
        }
        VehicleHeroModel.MODEL_X -> {
            moveTo(width * 0.10f, height * 0.69f)
            cubicTo(width * 0.14f, height * 0.47f, width * 0.27f, height * 0.40f, width * 0.37f, height * 0.38f)
            cubicTo(width * 0.43f, height * 0.21f, width * 0.57f, height * 0.16f, width * 0.72f, height * 0.25f)
            cubicTo(width * 0.81f, height * 0.30f, width * 0.85f, height * 0.46f, width * 0.92f, height * 0.52f)
        }
        VehicleHeroModel.MODEL_S -> {
            moveTo(width * 0.10f, height * 0.68f)
            cubicTo(width * 0.18f, height * 0.55f, width * 0.30f, height * 0.50f, width * 0.42f, height * 0.45f)
            cubicTo(width * 0.49f, height * 0.31f, width * 0.62f, height * 0.27f, width * 0.73f, height * 0.34f)
            cubicTo(width * 0.80f, height * 0.38f, width * 0.84f, height * 0.50f, width * 0.91f, height * 0.54f)
        }
        else -> {
            moveTo(width * 0.14f, height * 0.68f)
            cubicTo(width * 0.20f, height * 0.54f, width * 0.31f, height * 0.48f, width * 0.40f, height * 0.44f)
            cubicTo(width * 0.47f, height * 0.30f, width * 0.58f, height * 0.24f, width * 0.70f, height * 0.32f)
            cubicTo(width * 0.76f, height * 0.36f, width * 0.80f, height * 0.48f, width * 0.88f, height * 0.52f)
        }
    }
    cubicTo(width * 0.93f, height * 0.56f, width * 0.92f, height * 0.69f, width * 0.87f, height * 0.72f)
    lineTo(width * 0.18f, height * 0.72f)
    close()
}

private fun heroPaintColor(code: String, accent: Color): Color = when (code) {
    "PPSW" -> Color(0xFFE8E9EA)
    "PMNG" -> Color(0xFF73777C)
    "PBSB", "PB01", "PB02" -> Color(0xFF24558A)
    "PPSB" -> Color(0xFF1D3557)
    "PPMR", "PR01" -> Color(0xFFB6424A)
    "PN00" -> Color(0xFF242629)
    "PN01" -> Color(0xFF111315)
    "PX02" -> Color(0xFF9BA2AA)
    else -> accent.copy(alpha = 0.88f)
}

@Composable
fun RouteIndicator(
    start: String,
    end: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary
) {
    Row(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(top = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .height(14.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiary)
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(
            verticalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.weight(1f)
        ) {
            Text(
                start,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                end,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Icon(
            imageVector = Icons.Default.DirectionsCar,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(20.dp)
        )
    }
}
