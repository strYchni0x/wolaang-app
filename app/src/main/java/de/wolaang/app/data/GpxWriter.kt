package de.wolaang.app.data

import de.wolaang.app.routing.RoutePoint
import de.wolaang.app.tracking.TrackPoint
import java.time.Instant
import java.util.Locale

/** Serializes a recorded track into a minimal GPX 1.1 document. */
object GpxWriter {

    fun write(name: String, points: List<TrackPoint>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="wolaang" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        append("  <trk>\n")
        append("    <name>").append(escape(name)).append("</name>\n")
        append("    <trkseg>\n")
        for (p in points) {
            append("      <trkpt lat=\"").append(p.lat).append("\" lon=\"").append(p.lon).append("\">\n")
            append("        <ele>").append(p.altitude).append("</ele>\n")
            append("        <time>").append(Instant.ofEpochMilli(p.time)).append("</time>\n")
            append("      </trkpt>\n")
        }
        append("    </trkseg>\n")
        append("  </trk>\n")
        append("</gpx>\n")
    }

    /** Serializes a planned route (no timestamps) into a GPX 1.1 track. */
    fun writeRoute(name: String, points: List<RoutePoint>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="wolaang" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
        append("  <trk>\n")
        append("    <name>").append(escape(name)).append("</name>\n")
        append("    <trkseg>\n")
        for (p in points) {
            // 6 decimals ≈ 0.1 m — avoids floating-point noise like 52.60846699999999
            append(String.format(Locale.ROOT, "      <trkpt lat=\"%.6f\" lon=\"%.6f\">", p.lat, p.lon))
            p.elevation?.let { append(String.format(Locale.ROOT, "<ele>%.1f</ele>", it)) }
            append("</trkpt>\n")
        }
        append("    </trkseg>\n")
        append("  </trk>\n")
        append("</gpx>\n")
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
