package com.matelink.ui.util

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint
import androidx.compose.ui.graphics.Color

/**
 * Shared utility for creating glow and dim bitmaps used in both the
 * dashboard car image and the home screen widget.
 */
object GlowBitmapRenderer {

    /**
     * Creates a glow bitmap from the alpha channel of the source bitmap.
     * The glow follows the shape of the non-transparent pixels.
     *
     * @param source The source bitmap with transparency
     * @param glowColor The color for the glow effect
     * @param glowRadius The radius of the blur effect in pixels
     * @return A new bitmap containing only the glow effect
     */
    fun createGlowBitmap(source: Bitmap, glowColor: Color, glowRadius: Float): Bitmap {
        // Create a larger bitmap to accommodate the glow extending beyond the original bounds
        val padding = (glowRadius * 2).toInt()
        val glowBitmap = Bitmap.createBitmap(
            source.width + padding * 2,
            source.height + padding * 2,
            Bitmap.Config.ARGB_8888
        )

        val canvas = AndroidCanvas(glowBitmap)

        // Extract alpha from source first
        val alphaBitmap = source.extractAlpha()

        // Create paint with blur effect - use OUTER blur for glow effect
        val glowPaint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.argb(
                (glowColor.alpha * 255).toInt(),
                (glowColor.red * 255).toInt(),
                (glowColor.green * 255).toInt(),
                (glowColor.blue * 255).toInt()
            )
            maskFilter = BlurMaskFilter(glowRadius, BlurMaskFilter.Blur.OUTER)
        }

        // Draw the blurred alpha multiple times for a stronger glow effect
        repeat(3) {
            canvas.drawBitmap(alphaBitmap, padding.toFloat(), padding.toFloat(), glowPaint)
        }

        alphaBitmap.recycle()

        return glowBitmap
    }

    /**
     * Creates a tight rim-light bitmap from the alpha channel of the source.
     * Uses NORMAL blur at a small radius to produce a sharp bright outline
     * that traces the car's silhouette — like TRON-style edge lighting.
     *
     * @param source The source bitmap with transparency
     * @param rimColor The color for the rim light
     * @param rimRadius The blur radius (small, e.g. 4-6f) for edge tightness
     * @param passes Number of times to draw for intensity (2-3 recommended)
     * @return A new bitmap containing only the rim light effect
     */
    fun createRimLightBitmap(
        source: Bitmap,
        rimColor: Color,
        rimRadius: Float = 5f,
        passes: Int = 3
    ): Bitmap {
        val padding = (rimRadius * 2).toInt()
        val rimBitmap = Bitmap.createBitmap(
            source.width + padding * 2,
            source.height + padding * 2,
            Bitmap.Config.ARGB_8888
        )
        val canvas = AndroidCanvas(rimBitmap)
        val alphaBitmap = source.extractAlpha()

        val rimPaint = Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.argb(
                (rimColor.alpha * 255).toInt(),
                (rimColor.red * 255).toInt(),
                (rimColor.green * 255).toInt(),
                (rimColor.blue * 255).toInt()
            )
            maskFilter = BlurMaskFilter(rimRadius, BlurMaskFilter.Blur.NORMAL)
        }

        repeat(passes) {
            canvas.drawBitmap(alphaBitmap, padding.toFloat(), padding.toFloat(), rimPaint)
        }

        alphaBitmap.recycle()
        return rimBitmap
    }

    /**
     * Creates a dimmed version of the bitmap by drawing a dark overlay on top.
     *
     * @param source The source bitmap
     * @param dimAlpha The opacity of the dark overlay (0f = no dimming, 1f = fully black)
     * @return A new bitmap with the dark overlay applied
     */
    fun createDimmedCarBitmap(source: Bitmap, dimAlpha: Float = 0.45f): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(result)
        canvas.drawBitmap(source, 0f, 0f, null)
        val overlayPaint = Paint().apply {
            color = android.graphics.Color.argb(
                (dimAlpha * 255).toInt(), 0, 0, 0
            )
        }
        canvas.drawRect(0f, 0f, source.width.toFloat(), source.height.toFloat(), overlayPaint)
        return result
    }
}
