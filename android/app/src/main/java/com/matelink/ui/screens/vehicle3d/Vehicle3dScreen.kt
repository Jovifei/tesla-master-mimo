package com.matelink.ui.screens.vehicle3d

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.matelink.R

/**
 * 3D Vehicle Viewer screen.
 *
 * Currently renders a placeholder wireframe Tesla model.
 * TODO: Integrate Filament for real .glb model rendering.
 * TODO: Animate door/window/charge port states.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Vehicle3dScreen(
    carId: Int = 1,
    onBack: () -> Unit = {}
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var rotationY by remember { mutableFloatStateOf(0f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    // Vehicle state (placeholder - will come from ViewModel)
    var chargePortOpen by remember { mutableStateOf(false) }
    var doorsOpen by remember { mutableStateOf(false) }
    var frunkOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.vehicle_3d_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 3D Viewport
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, rotation ->
                            scale = (scale * zoom).coerceIn(0.5f, 3f)
                            // Clamp offset to keep model within viewport
                            offsetX = (offsetX + pan.x).coerceIn(-800f, 800f)
                            offsetY = (offsetY + pan.y).coerceIn(-800f, 800f)
                            rotationY = ((rotationY + rotation) % 360f + 360f) % 360f
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                // Placeholder 3D wireframe
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY,
                            rotationY = rotationY
                        )
                ) {
                    drawTeslaWireframe(
                        center = Offset(size.width / 2, size.height / 2),
                        scale = size.minDimension / 4,
                        doorsOpen = doorsOpen,
                        frunkOpen = frunkOpen,
                        chargePortOpen = chargePortOpen
                    )
                }

                // Gesture hint (epsilon comparison for float imprecision)
                if (kotlin.math.abs(offsetX) < 0.5f && kotlin.math.abs(offsetY) < 0.5f && kotlin.math.abs(scale - 1f) < 0.01f) {
                    Text(
                        text = stringResource(R.string.vehicle_3d_gesture_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)
                    )
                }
            }

            // Vehicle Controls
            ElevatedCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.vehicle_3d_controls),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ControlButton(
                            label = if (doorsOpen) stringResource(R.string.vehicle_3d_close_doors) else stringResource(R.string.vehicle_3d_open_doors),
                            isActive = doorsOpen,
                            onClick = { doorsOpen = !doorsOpen },
                            modifier = Modifier.weight(1f)
                        )
                        ControlButton(
                            label = if (chargePortOpen) stringResource(R.string.vehicle_3d_close_port) else stringResource(R.string.vehicle_3d_open_port),
                            isActive = chargePortOpen,
                            onClick = { chargePortOpen = !chargePortOpen },
                            modifier = Modifier.weight(1f)
                        )
                        ControlButton(
                            label = if (frunkOpen) stringResource(R.string.vehicle_3d_close_frunk) else stringResource(R.string.vehicle_3d_open_frunk),
                            isActive = frunkOpen,
                            onClick = { frunkOpen = !frunkOpen },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Reset view button
                    OutlinedButton(
                        onClick = {
                            scale = 1f
                            rotationY = 0f
                            offsetX = 0f
                            offsetY = 0f
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.vehicle_3d_reset_view))
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlButton(
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isActive)
                MaterialTheme.colorScheme.error
            else
                MaterialTheme.colorScheme.primary
        ),
        modifier = modifier
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Draw a placeholder Tesla wireframe (top-down simplified view).
 * Will be replaced by real Filament 3D rendering.
 */
private fun DrawScope.drawTeslaWireframe(
    center: Offset,
    scale: Float,
    doorsOpen: Boolean,
    frunkOpen: Boolean,
    chargePortOpen: Boolean
) {
    val bodyColor = Color(0xFF424242)
    val accentColor = Color(0xFF1E88E5)
    val glassColor = Color(0xFF90CAF9).copy(alpha = 0.5f)

    // Car body (simplified rectangle)
    val bodyWidth = scale * 2.5f
    val bodyHeight = scale * 1.2f

    drawRoundRect(
        color = bodyColor,
        topLeft = Offset(center.x - bodyWidth / 2, center.y - bodyHeight / 2),
        size = Size(bodyWidth, bodyHeight),
        cornerRadius = CornerRadius(scale * 0.3f)
    )

    // Windshield
    drawRoundRect(
        color = glassColor,
        topLeft = Offset(center.x - bodyWidth * 0.35f, center.y - bodyHeight * 0.4f),
        size = Size(bodyWidth * 0.4f, bodyHeight * 0.35f),
        cornerRadius = CornerRadius(scale * 0.1f)
    )

    // Rear window
    drawRoundRect(
        color = glassColor,
        topLeft = Offset(center.x + bodyWidth * 0.05f, center.y - bodyHeight * 0.4f),
        size = Size(bodyWidth * 0.3f, bodyHeight * 0.3f),
        cornerRadius = CornerRadius(scale * 0.1f)
    )

    // Wheels
    val wheelRadius = scale * 0.2f
    val wheelPositions = listOf(
        Offset(center.x - bodyWidth * 0.35f, center.y - bodyHeight * 0.55f),
        Offset(center.x + bodyWidth * 0.35f, center.y - bodyHeight * 0.55f),
        Offset(center.x - bodyWidth * 0.35f, center.y + bodyHeight * 0.55f),
        Offset(center.x + bodyWidth * 0.35f, center.y + bodyHeight * 0.55f)
    )
    wheelPositions.forEach { pos ->
        drawCircle(color = Color(0xFF212121), radius = wheelRadius, center = pos)
        drawCircle(color = Color(0xFF757575), radius = wheelRadius * 0.6f, center = pos)
    }

    // Headlights
    drawCircle(
        color = accentColor,
        radius = scale * 0.08f,
        center = Offset(center.x - bodyWidth * 0.48f, center.y - bodyHeight * 0.2f)
    )
    drawCircle(
        color = accentColor,
        radius = scale * 0.08f,
        center = Offset(center.x - bodyWidth * 0.48f, center.y + bodyHeight * 0.2f)
    )

    // Taillights
    drawCircle(
        color = Color(0xFFE53935),
        radius = scale * 0.06f,
        center = Offset(center.x + bodyWidth * 0.48f, center.y - bodyHeight * 0.25f)
    )
    drawCircle(
        color = Color(0xFFE53935),
        radius = scale * 0.06f,
        center = Offset(center.x + bodyWidth * 0.48f, center.y + bodyHeight * 0.25f)
    )

    // Door indicators (small rectangles offset from body sides)
    if (doorsOpen) {
        val doorColor = Color(0xFFFFA726).copy(alpha = 0.7f)
        val doorWidth = scale * 0.35f
        val doorHeight = scale * 0.45f
        val doorOffset = bodyWidth * 0.52f
        // Left front door
        drawRoundRect(
            color = doorColor,
            topLeft = Offset(center.x - doorOffset, center.y - bodyHeight * 0.45f),
            size = Size(doorWidth * 0.15f, doorHeight),
            cornerRadius = CornerRadius(scale * 0.04f)
        )
        // Right front door
        drawRoundRect(
            color = doorColor,
            topLeft = Offset(center.x + doorOffset - doorWidth * 0.15f, center.y - bodyHeight * 0.45f),
            size = Size(doorWidth * 0.15f, doorHeight),
            cornerRadius = CornerRadius(scale * 0.04f)
        )
        // Left rear door
        drawRoundRect(
            color = doorColor,
            topLeft = Offset(center.x - doorOffset, center.y + bodyHeight * 0.05f),
            size = Size(doorWidth * 0.15f, doorHeight),
            cornerRadius = CornerRadius(scale * 0.04f)
        )
        // Right rear door
        drawRoundRect(
            color = doorColor,
            topLeft = Offset(center.x + doorOffset - doorWidth * 0.15f, center.y + bodyHeight * 0.05f),
            size = Size(doorWidth * 0.15f, doorHeight),
            cornerRadius = CornerRadius(scale * 0.04f)
        )
    }

    // Frunk (front hood) indicator
    if (frunkOpen) {
        val hoodColor = Color(0xFFFFA726).copy(alpha = 0.7f)
        val hoodWidth = bodyWidth * 0.3f
        val hoodHeight = bodyHeight * 0.15f
        drawRoundRect(
            color = hoodColor,
            topLeft = Offset(center.x - bodyWidth * 0.45f, center.y - hoodHeight / 2),
            size = Size(hoodWidth, hoodHeight),
            cornerRadius = CornerRadius(scale * 0.06f)
        )
    }

    // Charge port indicator
    if (chargePortOpen) {
        drawCircle(
            color = Color(0xFF4CAF50),
            radius = scale * 0.12f,
            center = Offset(center.x + bodyWidth * 0.3f, center.y - bodyHeight * 0.55f)
        )
    }
}
