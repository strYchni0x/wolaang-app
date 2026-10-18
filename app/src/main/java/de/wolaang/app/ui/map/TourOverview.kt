package de.wolaang.app.ui.map

import android.graphics.Color
import android.location.Location
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.wolaang.app.data.GpxReader
import de.wolaang.app.data.model.Tour
import de.wolaang.app.data.model.TourType
import java.util.Calendar
import java.util.Locale
import org.osmdroid.util.GeoPoint

/** Which recorded tours the map overview shows. */
enum class OverviewFilter(val label: String) {
    ALL("Alle"),
    HIKE("Wandern"),
    BIKE("Rad");

    fun matches(type: TourType) = when (this) {
        ALL -> true
        HIKE -> type == TourType.HIKE
        BIKE -> type == TourType.BIKE
    }
}

/** What the Tours tab asks the map to show: tour type, optionally one year and one country. */
data class OverviewRequest(val filter: OverviewFilter, val year: Int? = null, val country: String? = null)

/** ISO codes stored in a tour's countries column ("DE,LU"; "-" or "" = none known). */
fun countryCodes(stored: String): List<String> = stored.split(',').filter { it.length >= 2 }

/** Calendar year (local time) of a timestamp in milliseconds. */
fun yearOf(millis: Long): Int = Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.YEAR)

/** A recorded tour in the overview, with a thinned-out line for drawing. */
class OverviewLine(val tour: Tour, val points: List<GeoPoint>)

/** Line colors in the overview: hikes purple, bike tours orange. */
fun overviewColor(type: TourType): Int =
    if (type == TourType.BIKE) Color.parseColor("#E65100") else Color.parseColor("#7B1FA2")

// Points closer than this to the last kept one are dropped; enough detail for an overview
// and keeps hundreds of tours smooth to pan.
private const val MIN_POINT_SPACING_M = 15f

/** Loads ([gpxOf]), parses and thins the tours matching the [request]; call off the main thread. */
suspend fun buildOverviewLines(
    tours: List<Tour>,
    request: OverviewRequest,
    gpxOf: suspend (Long) -> String,
): List<OverviewLine> =
    tours.filter {
        request.filter.matches(it.type) &&
            (request.year == null || yearOf(it.startedAt) == request.year) &&
            (request.country == null || request.country in countryCodes(it.countries))
    }.mapNotNull { tour ->
        val track = runCatching { GpxReader.readTrack(gpxOf(tour.id)) }.getOrNull() ?: return@mapNotNull null
        val kept = ArrayList<GeoPoint>()
        val dist = FloatArray(1)
        track.forEachIndexed { i, p ->
            val last = kept.lastOrNull()
            if (last != null && i != track.lastIndex) {
                Location.distanceBetween(last.latitude, last.longitude, p.lat, p.lon, dist)
                if (dist[0] < MIN_POINT_SPACING_M) return@forEachIndexed
            }
            kept.add(GeoPoint(p.lat, p.lon))
        }
        if (kept.size >= 2) OverviewLine(tour, kept) else null
    }

/** Top card of the overview: filter chips, count/length, close. */
@Composable
fun OverviewCard(
    filter: OverviewFilter,
    // Year / country chosen in the Tours tab, e.g. "2025 · Luxemburg", or null.
    scope: String?,
    lines: List<OverviewLine>,
    loading: Boolean,
    onFilter: (OverviewFilter) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp, top = 8.dp, bottom = 10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OverviewFilter.entries.forEach { f ->
                        FilterChip(selected = f == filter, onClick = { onFilter(f) }, label = { Text(f.label) })
                    }
                }
                Text(
                    when {
                        loading -> "Touren werden geladen …"
                        lines.isEmpty() -> "Keine aufgezeichneten Touren."
                        else -> String.format(
                            Locale.GERMANY,
                            "%s%d %s · %.0f km · Linie antippen für Details",
                            if (scope != null) "$scope: " else "",
                            lines.size,
                            if (lines.size == 1) "Tour" else "Touren",
                            lines.sumOf { it.tour.distanceMeters.toDouble() } / 1000.0,
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Clear, contentDescription = "Übersicht schließen")
            }
        }
    }
}
