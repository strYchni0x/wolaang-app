package de.wolaang.app.geo

import android.content.Context
import android.location.Location
import android.os.Build
import java.io.DataInputStream

/**
 * EGM96 geoid heights (public domain, 30' grid in assets/geoid/egm96-30.bin) to turn GPS
 * heights above the WGS84 ellipsoid into heights above sea level. In Germany the two differ
 * by about 40-50 m.
 */
class GeoidModel(private val context: Context) {

    private class Grid(val rows: Int, val cols: Int, val centimeters: ShortArray)

    private val grid: Grid by lazy {
        DataInputStream(context.assets.open("geoid/egm96-30.bin").buffered()).use { input ->
            val rows = input.readInt()
            val cols = input.readInt()
            Grid(rows, cols, ShortArray(rows * cols) { input.readShort() })
        }
    }

    /** Geoid height (meters above the ellipsoid) at the point, bilinearly interpolated. */
    fun undulation(lat: Double, lon: Double): Double {
        val g = grid
        val y = (90.0 - lat.coerceIn(-90.0, 90.0)) / STEP_DEG
        val x = ((lon % 360.0 + 360.0) % 360.0) / STEP_DEG
        val r = minOf(y.toInt(), g.rows - 2)
        val c = x.toInt()
        val fy = y - r
        val fx = x - c
        fun at(row: Int, col: Int) = g.centimeters[row * g.cols + col % g.cols] / 100.0
        return at(r, c) * (1 - fx) * (1 - fy) + at(r, c + 1) * fx * (1 - fy) +
            at(r + 1, c) * (1 - fx) * fy + at(r + 1, c + 1) * fx * fy
    }

    /** Height above sea level of a fix, or null if it has no altitude. */
    fun seaLevelAltitude(location: Location): Double? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && location.hasMslAltitude()) {
            return location.mslAltitudeMeters
        }
        if (!location.hasAltitude()) return null
        return location.altitude - undulation(location.latitude, location.longitude)
    }

    private companion object {
        const val STEP_DEG = 0.5
    }
}
