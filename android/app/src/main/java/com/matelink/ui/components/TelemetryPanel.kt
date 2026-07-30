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
        shadowElevation = 0.dp,
        border = BorderStroke(
            1.dp,
            Brush.linearGradient(
                listOf(
                    accent.copy(alpha = 0.34f),
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f)
                )
            )
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
    accent: Color = MaterialTheme.colorScheme.primary
) {
    val body = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f)
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

        val car = Path().apply {
            moveTo(w * 0.14f, h * 0.68f)
            cubicTo(w * 0.20f, h * 0.54f, w * 0.31f, h * 0.48f, w * 0.40f, h * 0.44f)
            cubicTo(w * 0.47f, h * 0.30f, w * 0.58f, h * 0.24f, w * 0.70f, h * 0.32f)
            cubicTo(w * 0.76f, h * 0.36f, w * 0.80f, h * 0.48f, w * 0.88f, h * 0.52f)
            cubicTo(w * 0.92f, h * 0.55f, w * 0.92f, h * 0.69f, w * 0.87f, h * 0.72f)
            lineTo(w * 0.18f, h * 0.72f)
            close()
        }
        drawPath(
            path = car,
            brush = Brush.linearGradient(
                colors = listOf(body, accent.copy(alpha = 0.78f), body),
                start = Offset(w * 0.18f, h * 0.38f),
                end = Offset(w * 0.86f, h * 0.74f)
            )
        )

        val glass = Path().apply {
            moveTo(w * 0.42f, h * 0.45f)
            cubicTo(w * 0.49f, h * 0.31f, w * 0.58f, h * 0.29f, w * 0.68f, h * 0.35f)
            lineTo(w * 0.75f, h * 0.49f)
            close()
        }
        drawPath(glass, color = window)
        drawLine(
            color = outline,
            start = Offset(w * 0.59f, h * 0.31f),
            end = Offset(w * 0.59f, h * 0.51f),
            strokeWidth = 1.dp.toPx()
        )

        listOf(w * 0.30f, w * 0.76f).forEach { wheelX ->
            drawCircle(color = wheel, radius = h * 0.13f, center = Offset(wheelX, h * 0.72f))
            drawCircle(color = outline, radius = h * 0.072f, center = Offset(wheelX, h * 0.72f))
            drawCircle(color = accent, radius = h * 0.025f, center = Offset(wheelX, h * 0.72f))
        }

        drawRoundRect(
            color = accent,
            topLeft = Offset(w * 0.845f, h * 0.56f),
            size = Size(w * 0.045f, h * 0.055f),
            cornerRadius = CornerRadius(4.dp.toPx())
        )
    }
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
