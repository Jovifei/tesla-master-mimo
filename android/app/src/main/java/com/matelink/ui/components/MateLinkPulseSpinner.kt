package com.matelink.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Transparent MateLink road mark with a light travelling over its cyan lane dashes. */
@Composable
fun MateLinkLoadingMark(
    modifier: Modifier = Modifier,
    size: Dp = 110.dp,
    pulseDurationMillis: Int = 1450,
    pulseColor: Color = ROAD_CYAN,
) {
    val route = remember {
        Path().apply {
            moveTo(457f, 88f)
            cubicTo(462f, 132f, 444f, 160f, 414f, 184f)
            cubicTo(389f, 204f, 365f, 216f, 338f, 216f)
        }
    }
    val routeMeasure = remember(route) {
        PathMeasure().apply { setPath(route, forceClosed = false) }
    }
    val transition = rememberInfiniteTransition(label = "mateLinkRoadPulse")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(pulseDurationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "mateLinkRoadProgress",
    )

    Box(modifier.size(width = size, height = size * (273f / 515f))) {
        Image(
            painter = painterResource(com.matelink.R.drawable.matelink_loading_logo),
            contentDescription = stringResource(com.matelink.R.string.loading),
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(Modifier.fillMaxSize()) {
            val xScale = this.size.width / 515f
            val yScale = this.size.height / 273f
            scale(xScale, yScale, pivot = Offset.Zero) {
                val distance = progress * routeMeasure.length
                val center = routeMeasure.getPosition(distance)
                for (step in 4 downTo 1) {
                    val trail = routeMeasure.getPosition(
                        (distance - step * 15f).coerceAtLeast(0f)
                    )
                    drawCircle(
                        color = pulseColor.copy(alpha = 0.09f * (5 - step)),
                        radius = 10f + step,
                        center = trail,
                    )
                }
                drawCircle(color = pulseColor.copy(alpha = 0.34f), radius = 10f, center = center)
                drawCircle(color = Color.White, radius = 3.5f, center = center)
            }
        }
    }
}

private val ROAD_CYAN = Color(0xFF00D3FA)

@Composable
fun rememberDebouncedLoading(loading: Boolean, delayMillis: Long = 200L): Boolean {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(loading) {
        if (loading) {
            delay(delayMillis)
            visible = true
        } else {
            visible = false
        }
    }
    return visible
}

@Composable
fun MateLinkLoadingPlaceholder(
    color: Color = MaterialTheme.colorScheme.primary,
    delayMillis: Long = 200L,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(delayMillis)
        visible = true
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .then(if (visible) Modifier.background(Color.Black.copy(alpha = 0.45f)) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (visible) MateLinkLoadingMark(modifier = Modifier.fillMaxWidth(0.46f), pulseColor = color)
    }
}
