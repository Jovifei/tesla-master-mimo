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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
    val dashCenters = remember {
        listOf(Offset(457f, 88f), Offset(451f, 143f), Offset(410f, 188f), Offset(341f, 216f))
    }
    val transition = rememberInfiniteTransition(label = "mateLinkRoadPulse")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 4f,
        animationSpec = infiniteRepeatable(
            animation = tween(pulseDurationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "mateLinkRoadProgress",
    )

    Box(modifier.width(size).aspectRatio(515f / 273f)) {
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
                val activeDash = progress.toInt().coerceIn(0, 3)
                val center = dashCenters[activeDash]
                drawCircle(color = pulseColor.copy(alpha = 0.22f), radius = 20f, center = center)
                drawCircle(color = pulseColor.copy(alpha = 0.38f), radius = 13f, center = center)
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
        if (visible) MateLinkLoadingMark(size = 180.dp, pulseColor = color)
    }
}
