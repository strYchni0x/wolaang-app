package de.wolaang.app.geo

import android.content.Context
import android.location.Location
import de.wolaang.app.routing.RoutePoint
import java.io.DataInputStream

/**
 * Offline country lookup from the Natural Earth 1:50m borders (public domain) in
 * assets/countries/countries.bin. Accurate to about a kilometer at borders, which is
 * enough to tell which countries a tour went through.
 */
class CountryLookup(private val context: Context) {

    private class Polygon(val rings: List<IntArray>) {
        // Bounding box of the outer ring, in 1e-4 degrees.
        val minLon: Int; val maxLon: Int; val minLat: Int; val maxLat: Int

        init {
            val outer = rings[0]
            var a = Int.MAX_VALUE; var b = Int.MIN_VALUE; var c = Int.MAX_VALUE; var d = Int.MIN_VALUE
            for (i in outer.indices step 2) {
                a = minOf(a, outer[i]); b = maxOf(b, outer[i])
                c = minOf(c, outer[i + 1]); d = maxOf(d, outer[i + 1])
            }
            minLon = a; maxLon = b; minLat = c; maxLat = d
        }

        fun contains(lon: Int, lat: Int): Boolean {
            if (lon < minLon || lon > maxLon || lat < minLat || lat > maxLat) return false
            if (!inRing(rings[0], lon, lat)) return false
            for (h in 1 until rings.size) if (inRing(rings[h], lon, lat)) return false
            return true
        }
    }

    private class Country(val code: String, val name: String, val polygons: List<Polygon>)

    private val countries: List<Country> by lazy { load() }

    /** German country name for an ISO code, e.g. "LU" → "Luxemburg". */
    fun nameOf(code: String): String = countries.firstOrNull { it.code == code }?.name ?: code

    fun countryAt(lat: Double, lon: Double): String? {
        val x = Math.round(lon * 1e4).toInt()
        val y = Math.round(lat * 1e4).toInt()
        return countries.firstOrNull { c -> c.polygons.any { it.contains(x, y) } }?.code
    }

    /**
     * ISO codes of the countries a track passes through, in order of first visit. Samples a
     * point about every kilometer (plus start and end).
     */
    fun countriesOf(track: List<RoutePoint>): List<String> {
        if (track.isEmpty()) return emptyList()
        val found = LinkedHashSet<String>()
        var last: RoutePoint? = null
        val dist = FloatArray(1)
        track.forEachIndexed { i, p ->
            val prev = last
            if (prev != null && i != track.lastIndex) {
                Location.distanceBetween(prev.lat, prev.lon, p.lat, p.lon, dist)
                if (dist[0] < SAMPLE_SPACING_M) return@forEachIndexed
            }
            last = p
            countryAt(p.lat, p.lon)?.let { found.add(it) }
        }
        return found.toList()
    }

    private fun load(): List<Country> =
        DataInputStream(context.assets.open("countries/countries.bin").buffered()).use { input ->
            List(input.readInt()) {
                val code = input.readUTF()
                val name = input.readUTF()
                val polygons = List(input.readInt()) {
                    Polygon(List(input.readInt()) { IntArray(input.readInt() * 2) { input.readInt() } })
                }
                Country(code, name, polygons)
            }
        }

    private companion object {
        const val SAMPLE_SPACING_M = 1000f

        /** Even-odd ray casting; [ring] holds lon/lat pairs. */
        fun inRing(ring: IntArray, x: Int, y: Int): Boolean {
            var inside = false
            val n = ring.size / 2
            var j = n - 1
            for (i in 0 until n) {
                val xi = ring[2 * i].toLong(); val yi = ring[2 * i + 1].toLong()
                val xj = ring[2 * j].toLong(); val yj = ring[2 * j + 1].toLong()
                if ((yi > y) != (yj > y)) {
                    // x coordinate of the edge at height y, compared without division.
                    val lhs = (x - xi) * (yj - yi)
                    val rhs = (xj - xi) * (y - yi)
                    if (if (yj > yi) lhs < rhs else lhs > rhs) inside = !inside
                }
                j = i
            }
            return inside
        }
    }
}
