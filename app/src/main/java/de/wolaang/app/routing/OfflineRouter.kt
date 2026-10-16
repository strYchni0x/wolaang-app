package de.wolaang.app.routing

import android.content.Context
import btools.router.RoutingContext
import btools.router.RoutingEngine
import btools.router.RoutingParamCollector
import de.wolaang.app.data.model.TourType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.floor

/** A point of a computed route; [elevation] is null where BRouter has no height data. */
data class RoutePoint(val lat: Double, val lon: Double, val elevation: Double?)

/** A routing data tile on the device. [lastModified] is the server's version date. */
data class SegmentInfo(val name: String, val sizeBytes: Long, val lastModified: Long)

data class RouteResult(
    val points: List<RoutePoint>,
    val distanceMeters: Int,
    val ascentMeters: Int,
    val durationSeconds: Int,
)

/**
 * Offline routing via the embedded BRouter engine.
 *
 * Layout in app-private storage (filesDir/brouter):
 *  - profiles2/  routing profiles + lookups.dat, copied from assets on first use
 *  - segments4/  routing data tiles (5°×5°, *.rd5), downloaded once per region
 *
 * Only the tile download touches the network; routing itself runs fully on the device.
 */
class OfflineRouter(context: Context) {

    private val appContext = context.applicationContext
    private val baseDir = File(appContext.filesDir, "brouter")
    private val profileDir = File(baseDir, "profiles2")
    val segmentDir = File(baseDir, "segments4")

    /** Tile names (e.g. "E5_N50") needed for the given points that are not on the device yet. */
    fun missingSegments(points: List<Pair<Double, Double>>): List<String> =
        points.map { (lat, lon) -> segmentName(lat, lon) }
            .distinct()
            .filterNot { File(segmentDir, "$it.rd5").exists() }

    /** Routing data tiles stored on the device, sorted by name. */
    fun downloadedSegments(): List<SegmentInfo> =
        segmentDir.listFiles { f -> f.isFile && f.name.endsWith(".rd5") }
            ?.map { SegmentInfo(it.name.removeSuffix(".rd5"), it.length(), it.lastModified()) }
            ?.sortedBy { it.name }
            ?: emptyList()

    fun deleteSegment(name: String) {
        File(segmentDir, "$name.rd5").delete()
    }

    /** Version date of a tile on brouter.de (HEAD request), or null if unknown. */
    suspend fun remoteLastModified(name: String): Long? = withContext(Dispatchers.IO) {
        val conn = URL("$SEGMENT_BASE_URL$name.rd5").openConnection() as HttpURLConnection
        conn.requestMethod = "HEAD"
        conn.setRequestProperty("User-Agent", appContext.packageName)
        try {
            if (conn.responseCode == HttpURLConnection.HTTP_OK) conn.lastModified.takeIf { it > 0 } else null
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Downloads one routing data tile from brouter.de. [onProgress] gets values 0..1.
     * Writes to a temp file first, so an aborted download never leaves a broken tile behind.
     */
    suspend fun downloadSegment(name: String, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        segmentDir.mkdirs()
        val target = File(segmentDir, "$name.rd5")
        val tmp = File(segmentDir, "$name.rd5.part")
        val conn = URL("$SEGMENT_BASE_URL$name.rd5").openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", appContext.packageName)
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                error("Download fehlgeschlagen (HTTP ${conn.responseCode})")
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
            // Replace an older version only once the new one is complete.
            target.delete()
            if (!tmp.renameTo(target)) error("Kachel konnte nicht gespeichert werden")
            // Keep the server's version date, so update checks can compare against it.
            conn.lastModified.takeIf { it > 0 }?.let { target.setLastModified(it) }
        } finally {
            conn.disconnect()
            tmp.delete()
        }
    }

    /** Computes a route through [waypoints] (lat, lon). Blocking work runs on [Dispatchers.Default]. */
    suspend fun route(waypoints: List<Pair<Double, Double>>, type: TourType): RouteResult =
        withContext(Dispatchers.Default) {
            require(waypoints.size >= 2) { "Mindestens Start und Ziel nötig" }
            ensureProfiles()

            val rc = RoutingContext()
            rc.localFunction = File(profileDir, profileFor(type)).absolutePath

            val wps = RoutingParamCollector().readPositions(
                waypoints.map { it.second }.toDoubleArray(),
                waypoints.map { it.first }.toDoubleArray(),
            )
            val engine = RoutingEngine(null, null, segmentDir, wps, rc, RoutingEngine.BROUTER_ENGINEMODE_ROUTING)
            engine.quite = true
            engine.doRun(MAX_RUNNING_TIME_MS)

            engine.errorMessage?.let { error(it) }
            val track = engine.foundTrack ?: error("Keine Route gefunden")

            RouteResult(
                points = track.nodes.map { node ->
                    RoutePoint(
                        lat = node.iLat / 1e6 - 90.0,
                        lon = node.iLon / 1e6 - 180.0,
                        elevation = if (node.sElev == Short.MIN_VALUE) null else node.elev,
                    )
                },
                distanceMeters = track.distance,
                ascentMeters = track.plainAscend,
                // BRouter's time model is made for cycling; use the DIN 33466 hiking rule on foot.
                durationSeconds = if (type == TourType.HIKE) {
                    hikingSeconds(track.distance, track.plainAscend)
                } else {
                    track.totalSeconds
                },
            )
        }

    /** Copies the bundled profiles to filesDir, since BRouter reads them as plain files. */
    private fun ensureProfiles() {
        profileDir.mkdirs()
        val assetDir = "brouter/profiles2"
        appContext.assets.list(assetDir)?.forEach { name ->
            val target = File(profileDir, name)
            if (!target.exists()) {
                appContext.assets.open("$assetDir/$name").use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
            }
        }
    }

    companion object {
        private const val SEGMENT_BASE_URL = "https://brouter.de/brouter/segments4/"
        private const val MAX_RUNNING_TIME_MS = 60_000L

        fun profileFor(type: TourType) = when (type) {
            TourType.HIKE -> "hiking-mountain.brf"
            TourType.BIKE -> "trekking.brf"
        }

        /**
         * Hiking time after DIN 33466: 4 km/h on the flat, 300 m/h uphill;
         * total = larger part + half of the smaller part.
         */
        fun hikingSeconds(distanceMeters: Int, ascentMeters: Int): Int {
            val flatH = distanceMeters / 4000.0
            val upH = ascentMeters / 300.0
            return ((maxOf(flatH, upH) + minOf(flatH, upH) / 2) * 3600).toInt()
        }

        /** Human-readable area of a tile, e.g. "E5_N50" → "50–55° N, 5–10° O". */
        fun describeSegment(name: String): String {
            val match = Regex("([EW])(\\d+)_([NS])(\\d+)").matchEntire(name) ?: return name
            val (ew, lon, ns, lat) = match.destructured
            val lonStart = lon.toInt()
            val latStart = lat.toInt()
            // West/south tiles are named by their (negative) lower bound, e.g. W5 = 5°W..0°.
            val lonRange = if (ew == "E") "$lonStart–${lonStart + 5}° O" else "${lonStart}–${lonStart - 5}° W"
            val latRange = if (ns == "N") "$latStart–${latStart + 5}° N" else "${latStart}–${latStart - 5}° S"
            return "$latRange, $lonRange"
        }

        /** BRouter tile name for a coordinate, e.g. (52.6, 8.4) → "E5_N50". */
        fun segmentName(lat: Double, lon: Double): String {
            val lonTile = floor(lon / 5).toInt() * 5
            val latTile = floor(lat / 5).toInt() * 5
            val lonPart = if (lonTile < 0) "W${-lonTile}" else "E$lonTile"
            val latPart = if (latTile < 0) "S${-latTile}" else "N$latTile"
            return "${lonPart}_$latPart"
        }
    }
}
