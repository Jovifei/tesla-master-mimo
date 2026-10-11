package com.matelink.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Pulse the original lane-stripe pixels; never move dots or redraw the brand geometry. */
@Composable
fun MateLinkLoadingMark(
    modifier: Modifier = Modifier,
    size: Dp = 110.dp,
    pulseDurationMillis: Int = 1450,
    // Kept for source compatibility; brand lane flashes stay white in every theme.
    @Suppress("UNUSED_PARAMETER") pulseColor: Color = Color.White,
) {
    val resources = LocalContext.current.resources
    val stripes = remember(resources) {
        val source = BitmapFactory.decodeResource(resources, com.matelink.R.drawable.matelink_loading_logo)
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        List(4) { stripe ->
            val mask = IntArray(pixels.size) { index ->
                val argb = pixels[index]
                val x = index % source.width
                val y = index / source.width
                if (loadingStripeIndex(argb, x, y, source.width, source.height) == stripe) {
                    (argb and 0xff000000.toInt()) or 0x00ffffff
                } else 0
            }
            Bitmap.createBitmap(mask, source.width, source.height, Bitmap.Config.ARGB_8888).asImageBitmap()
        }.also { source.recycle() }
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
            stripes.forEachIndexed { index, stripe ->
                drawImage(
                    image = stripe,
                    dstSize = IntSize(this.size.width.toInt(), this.size.height.toInt()),
                    alpha = loadingStripeAlpha(progress, index),
                )
            }
        }
    }
}


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
