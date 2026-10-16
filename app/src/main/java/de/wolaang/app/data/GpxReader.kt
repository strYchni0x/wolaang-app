package de.wolaang.app.data

import android.util.Xml
import de.wolaang.app.routing.RoutePoint
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/** Reads the track points (lat, lon, optional elevation) back out of a GPX document. */
object GpxReader {

    fun readTrack(gpx: String): List<RoutePoint> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(gpx))

        val points = ArrayList<RoutePoint>()
        var lat = 0.0
        var lon = 0.0
        var ele: Double? = null
        var inPoint = false

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "trkpt", "rtept" -> {
                        lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: continue
                        lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: continue
                        ele = null
                        inPoint = true
                    }
                    "ele" -> if (inPoint) ele = parser.nextText().trim().toDoubleOrNull()
                }
                XmlPullParser.END_TAG -> if (inPoint && (parser.name == "trkpt" || parser.name == "rtept")) {
                    points.add(RoutePoint(lat, lon, ele))
                    inPoint = false
                }
            }
        }
        return points
    }
}
