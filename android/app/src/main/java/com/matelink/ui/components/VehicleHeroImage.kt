package com.matelink.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.matelink.R
import com.matelink.domain.model.CarImageResolver
import java.io.File

@Composable
fun VehicleHeroImage(
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    model: String? = null,
    exteriorColor: String? = null,
    wheelType: String? = null,
    trimBadging: String? = null,
    customPhotoFile: File? = null,
    onPickPhotoRequested: () -> Unit = {},
    onResetPhotoRequested: () -> Unit = {}
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val compositorUrl = remember(model, exteriorColor, wheelType, trimBadging) {
        CarImageResolver.getCompositorUrl(model, exteriorColor, wheelType, trimBadging)
    }
    val imageRequest = remember(compositorUrl) {
        coil.request.ImageRequest.Builder(context)
            .data(compositorUrl)
            .crossfade(true)
            .listener(
                onStart = { android.util.Log.i("VehicleHeroImage", "Coil start: $compositorUrl") },
                onSuccess = { _, result -> android.util.Log.i("VehicleHeroImage", "Coil success: ${result.dataSource}") },
                onError = { _, result -> android.util.Log.w("VehicleHeroImage", "Coil error: ${result.throwable.message}", result.throwable) }
            )
            .build()
    }

    var showMenu by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(165.dp),
        contentAlignment = Alignment.Center
    ) {
        Crossfade(
            targetState = customPhotoFile,
            animationSpec = tween(400),
            label = "vehicle_hero_image_crossfade"
        ) { photoFile ->
            if (photoFile != null && photoFile.exists()) {
                SubcomposeAsyncImage(
                    model = photoFile,
                    contentDescription = stringResource(R.string.vehicle),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth(0.96f)
                        .height(155.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { showMenu = true },
                    loading = {
                        VehicleHeroGraphic(
                            accent = accent,
                            model = model,
                            exteriorColor = exteriorColor,
                            wheelType = wheelType,
                            trimBadging = trimBadging
                        )
                    },
                    error = {
                        VehicleHeroGraphic(
                            accent = accent,
                            model = model,
                            exteriorColor = exteriorColor,
                            wheelType = wheelType,
                            trimBadging = trimBadging
                        )
                    }
                )
            } else {
                SubcomposeAsyncImage(
                    model = imageRequest,
                    contentDescription = stringResource(R.string.vehicle),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth(0.96f)
                        .height(155.dp)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { showMenu = true },
                    loading = {
                        VehicleHeroGraphic(
                            accent = accent,
                            model = model,
                            exteriorColor = exteriorColor,
                            wheelType = wheelType,
                            trimBadging = trimBadging
                        )
                    },
                    error = {
                        VehicleHeroGraphic(
                            accent = accent,
                            model = model,
                            exteriorColor = exteriorColor,
                            wheelType = wheelType,
                            trimBadging = trimBadging
                        )
                    }
                )
            }
        }

        // Action icon in corner to prompt custom vehicle photo replacement
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = 4.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
                shadowElevation = 2.dp,
                modifier = Modifier.size(34.dp)
            ) {
                IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = stringResource(R.string.edit_vehicle_photo),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_choose_custom_photo)) },
                    onClick = {
                        showMenu = false
                        onPickPhotoRequested()
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.AddPhotoAlternate,
                            contentDescription = null
                        )
                    }
                )
                if (customPhotoFile != null && customPhotoFile.exists()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_restore_default_photo)) },
                        onClick = {
                            showMenu = false
                            onResetPhotoRequested()
                        }
                    )
                }
            }
        }
    }
}
