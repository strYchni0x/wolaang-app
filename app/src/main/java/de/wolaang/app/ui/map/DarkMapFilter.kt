package de.wolaang.app.ui.map

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import org.osmdroid.views.MapView

/**
 * Dark map without a separate tile source: the standard OSM tiles are inverted (light
 * background → dark) and then hue-rotated by 180°, which brings the original hues back
 * (forests stay green, water stays blue). A slight dimming keeps it calm at night.
 */
private val DARK_TILES_FILTER: ColorMatrixColorFilter by lazy {
    val invert = ColorMatrix(
        floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
    // Luminance-preserving hue rotation by 180° (cos = -1, sin = 0), as in SVG feColorMatrix.
    val hueRotate180 = ColorMatrix(
        floatArrayOf(
            -0.574f, 1.430f, 0.144f, 0f, 0f,
            0.426f, 0.430f, 0.144f, 0f, 0f,
            0.426f, 1.430f, -0.856f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    )
    val dim = ColorMatrix().apply { setScale(0.85f, 0.85f, 0.85f, 1f) }

    val combined = ColorMatrix(invert).apply {
        postConcat(hueRotate180)
        postConcat(dim)
    }
    ColorMatrixColorFilter(combined)
}

/** Switches the map tiles between their normal look and the dark variant. */
fun MapView.setDarkTiles(dark: Boolean) {
    overlayManager.tilesOverlay.setColorFilter(if (dark) DARK_TILES_FILTER else null)
    invalidate()
}
