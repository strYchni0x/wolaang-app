package de.wolaang.app.data

import android.util.Xml
import de.wolaang.app.routing.RoutePoint
import org.xmlpull.v1.XmlPullParser
import java.io.Reader
import java.io.StringReader
import java.time.Instant
import java.time.OffsetDateTime

/** One GPX point; [time] (epoch millis) is only present in recorded tracks. */
data class GpxPoint(val lat: Double, val lon: Double, val elevation: Double?, val time: Long?)

/** Contents of a GPX file: the first name found (track, route or metadata) and all points in order. */
data class GpxTrack(val name: String?, val points: List<GpxPoint>)

/** Reads GPX documents — our own as well as foreign ones (Komoot, OsmAnd, Garmin …). */
object GpxReader {

    /** Track points of a stored GPX document, for drawing on the map. */
    fun readTrack(gpx: String): List<RoutePoint> =
        read(StringReader(gpx)).points.map { RoutePoint(it.lat, it.lon, it.elevation) }

    /**
     * Parses track points (trkpt) and route points (rtept) of all tracks/segments in order.
     * Throws if the input is not XML; returns an empty track if it contains no points.
     */
    fun read(input: Reader): GpxTrack {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input)

        val points = ArrayList<GpxPoint>()
        var name: String? = null
        var lat = 0.0
        var lon = 0.0
        var ele: Double? = null
        var time: Long? = null
        var inPoint = false
        var inWaypoint = false

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "trkpt", "rtept" -> {
                        lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: continue
                        lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: continue
                        ele = null
                        time = null
                        inPoint = true
                    }
                    "wpt" -> inWaypoint = true
                    "ele" -> if (inPoint) ele = parser.nextText().trim().toDoubleOrNull()
                    "time" -> if (inPoint) time = parseTime(parser.nextText().trim())
                    // Names of single waypoints (<wpt>) or points are not the tour's name.
                    "name" -> if (!inPoint && !inWaypoint && name == null) {
                        name = parser.nextText().trim().takeIf { it.isNotEmpty() }
                    }
                }
                XmlPullParser.END_TAG -> when {
                    inPoint && (parser.name == "trkpt" || parser.name == "rtept") -> {
                        points.add(GpxPoint(lat, lon, ele, time))
                        inPoint = false
                    }
                    parser.name == "wpt" -> inWaypoint = false
                }
            }
        }
        return GpxTrack(name, points)
    }

    // "2026-10-16T08:00:00Z" is the norm, but some exporters write an offset ("+02:00").
    private fun parseTime(text: String): Long? =
        runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
}
