package de.wolaang.app.data

import android.content.ContentResolver
import android.location.Location
import android.net.Uri
import android.provider.OpenableColumns
import de.wolaang.app.data.db.PlannedRouteDao
import de.wolaang.app.data.db.TourDao
import de.wolaang.app.data.model.PlannedRoute
import de.wolaang.app.data.model.Tour
import de.wolaang.app.data.model.TourType
import de.wolaang.app.routing.OfflineRouter
import de.wolaang.app.routing.RoutePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParserException
import kotlin.math.cos

/** Where imported GPX files end up. */
enum class ImportTarget { PLANNED, RECORDED }

/** null = guess hike/bike per file. */
data class ImportOptions(val target: ImportTarget, val type: TourType?)

/** [skipped]: files already in the app (e.g. when importing the same folder again). */
data class ImportResult(val imported: Int, val skipped: Int, val failed: List<String>)

/**
 * Imports GPX files (e.g. a Komoot export) either as planned routes — editable in the
 * Plan tab, via simplified support points — or as recorded tours with their original data.
 */
class GpxImporter(
    private val resolver: ContentResolver,
    private val tourDao: TourDao,
    private val plannedRouteDao: PlannedRouteDao,
) {

    suspend fun import(uris: List<Uri>, options: ImportOptions): ImportResult = withContext(Dispatchers.IO) {
        var imported = 0
        var skipped = 0
        val failed = ArrayList<String>()
        for (uri in uris) {
            val fileName = displayName(uri)
            try {
                val text = resolver.openInputStream(uri)?.use { it.reader().readText() }
                    ?: error("nicht lesbar")
                val track = GpxReader.read(text.reader())
                require(track.points.size >= 2) { "keine Strecke enthalten" }
                val name = track.name ?: fileName.removeSuffix(".gpx").removeSuffix(".GPX")
                val added = when (options.target) {
                    ImportTarget.PLANNED -> importPlanned(name, track, options.type)
                    ImportTarget.RECORDED -> importRecorded(name, track, text, options.type)
                }
                if (added) imported++ else skipped++
            } catch (e: XmlPullParserException) {
                failed.add("$fileName: keine gültige GPX-Datei")
            } catch (e: Exception) {
                failed.add("$fileName: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        ImportResult(imported, skipped, failed)
    }

    /** Returns false if the route is already there (same name, about the same length). */
    private suspend fun importPlanned(name: String, track: GpxTrack, typeChoice: TourType?): Boolean {
        val stats = TrackStats.of(track.points)
        val distance = stats.distanceMeters.toFloat()
        if (plannedRouteDao.hasRouteNamed(name, distance, distanceTolerance(distance))) return false
        val type = typeChoice ?: guessType(name, stats)
        // The Plan tab routes through these points; keep few so the pins stay readable.
        val support = simplify(track.points, MAX_SUPPORT_POINTS)
        val routePoints = track.points.map { RoutePoint(it.lat, it.lon, it.elevation) }
        plannedRouteDao.insertPlannedRoute(
            PlannedRoute(
                name = name,
                type = type,
                createdAt = System.currentTimeMillis(),
                waypointsJson = support.joinToString(",", "[", "]") { "[${it.lat},${it.lon}]" },
                distanceMeters = stats.distanceMeters.toFloat(),
                ascentMeters = stats.ascentMeters.toInt(),
                estimatedDurationSeconds = when (type) {
                    TourType.HIKE -> OfflineRouter.hikingSeconds(stats.distanceMeters.toInt(), stats.ascentMeters.toInt())
                    TourType.BIKE -> (stats.distanceMeters / BIKE_SPEED_MPS).toInt()
                }.toLong(),
                gpxData = GpxWriter.writeRoute(name, routePoints),
                notes = "Importiert",
            )
        )
        return true
    }

    /**
     * Returns false if the tour is already there: same start time (to the minute) and about
     * the same length, or, for tracks without timestamps, same name and length.
     */
    private suspend fun importRecorded(name: String, track: GpxTrack, originalGpx: String, typeChoice: TourType?): Boolean {
        val stats = TrackStats.of(track.points)
        val distance = stats.distanceMeters.toFloat()
        val trackStart = track.points.firstNotNullOfOrNull { it.time }
        val exists = if (trackStart != null) {
            tourDao.hasTourAt(trackStart, SAME_START_MS, distance, distanceTolerance(distance))
        } else {
            tourDao.hasTourNamed(name, distance, distanceTolerance(distance))
        }
        if (exists) return false
        val start = trackStart ?: System.currentTimeMillis()
        val end = track.points.lastOrNull { it.time != null }?.time
        tourDao.insertTour(
            Tour(
                name = name,
                type = typeChoice ?: guessType(name, stats),
                startedAt = start,
                finishedAt = end,
                distanceMeters = stats.distanceMeters.toFloat(),
                durationSeconds = if (end != null) (end - start) / 1000 else 0,
                elevationGainMeters = stats.ascentMeters.toFloat(),
                // Keep the file as it was (timestamps, extensions) — it's the user's record.
                gpxData = originalGpx,
                notes = "Importiert",
            )
        )
        return true
    }

    // Length differences up to this count as the same track (GPS noise, rounding).
    private fun distanceTolerance(distance: Float) = maxOf(50f, distance * 0.05f)

    private fun displayName(uri: Uri): String =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "GPX"

    /** Distance, elevation gain (with 3 m hysteresis against GPS noise) and moving speed. */
    private data class TrackStats(val distanceMeters: Double, val ascentMeters: Double, val durationSeconds: Long?) {
        companion object {
            fun of(points: List<GpxPoint>): TrackStats {
                val d = FloatArray(1)
                var distance = 0.0
                var ascent = 0.0
                var reference: Double? = null
                points.forEachIndexed { i, p ->
                    if (i > 0) {
                        val q = points[i - 1]
                        Location.distanceBetween(q.lat, q.lon, p.lat, p.lon, d)
                        distance += d[0]
                    }
                    p.elevation?.let { e ->
                        val ref = reference
                        when {
                            ref == null -> reference = e
                            e - ref >= ELEVATION_HYSTERESIS_M -> { ascent += e - ref; reference = e }
                            e < ref -> reference = e
                        }
                    }
                }
                val t0 = points.firstNotNullOfOrNull { it.time }
                val t1 = points.lastOrNull { it.time != null }?.time
                val duration = if (t0 != null && t1 != null && t1 > t0) (t1 - t0) / 1000 else null
                return TrackStats(distance, ascent, duration)
            }
        }
    }

    private fun guessType(name: String, stats: TrackStats): TourType {
        // Recorded tracks: average speed tells walking from cycling quite reliably.
        stats.durationSeconds?.takeIf { it > 0 }?.let { seconds ->
            return if (stats.distanceMeters / seconds > BIKE_GUESS_SPEED_MPS) TourType.BIKE else TourType.HIKE
        }
        return if (BIKE_WORDS.containsMatchIn(name.lowercase())) TourType.BIKE else TourType.HIKE
    }

    /**
     * Douglas-Peucker with a growing tolerance until at most [maxPoints] remain;
     * start and end are always kept.
     */
    private fun simplify(points: List<GpxPoint>, maxPoints: Int): List<GpxPoint> {
        var tolerance = 10.0
        var result: List<GpxPoint>
        do {
            result = douglasPeucker(points, tolerance)
            tolerance *= 1.5
        } while (result.size > maxPoints)
        return result
    }

    private fun douglasPeucker(points: List<GpxPoint>, toleranceMeters: Double): List<GpxPoint> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        val stack = ArrayDeque<Pair<Int, Int>>().apply { add(0 to points.lastIndex) }
        while (stack.isNotEmpty()) {
            val (from, to) = stack.removeLast()
            var maxDist = 0.0
            var index = -1
            for (i in from + 1 until to) {
                val dist = distanceToSegment(points[i], points[from], points[to])
                if (dist > maxDist) { maxDist = dist; index = i }
            }
            if (index >= 0 && maxDist > toleranceMeters) {
                keep[index] = true
                stack.add(from to index)
                stack.add(index to to)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /** Distance in meters of [p] to segment [a]-[b], on a local flat projection (fine for a few km). */
    private fun distanceToSegment(p: GpxPoint, a: GpxPoint, b: GpxPoint): Double {
        val mPerDegLat = 111_320.0
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(a.lat))
        val bx = (b.lon - a.lon) * mPerDegLon
        val by = (b.lat - a.lat) * mPerDegLat
        val px = (p.lon - a.lon) * mPerDegLon
        val py = (p.lat - a.lat) * mPerDegLat
        val lenSq = bx * bx + by * by
        if (lenSq == 0.0) return Math.hypot(px, py)
        val t = ((px * bx + py * by) / lenSq).coerceIn(0.0, 1.0)
        return Math.hypot(px - t * bx, py - t * by)
    }

    private companion object {
        const val MAX_SUPPORT_POINTS = 12
        const val SAME_START_MS = 60_000L
        const val BIKE_SPEED_MPS = 16 / 3.6           // planning estimate for cycling
        const val BIKE_GUESS_SPEED_MPS = 9 / 3.6       // faster average than this = bike
        // Word starts only, so "Radtour" matches but "Paradies" doesn't.
        val BIKE_WORDS = Regex("\\b(rad|fahrrad|rennrad|bike|mtb|gravel|cycling|velo)")
    }
}
