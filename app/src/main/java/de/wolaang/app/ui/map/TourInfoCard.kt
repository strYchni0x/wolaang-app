package de.wolaang.app.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.wolaang.app.data.descentOf
import de.wolaang.app.data.model.Tour
import de.wolaang.app.data.model.TourType
import de.wolaang.app.routing.RoutePoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Descent and lowest/highest point of a track; null without elevation data. */
private class ElevationFigures(val descentMeters: Int, val minMeters: Int, val maxMeters: Int)

private fun elevationFigures(points: List<RoutePoint>): ElevationFigures? {
    val elevations = points.mapNotNull { it.elevation }
    if (elevations.size < 2) return null
    return ElevationFigures(descentOf(elevations).toInt(), elevations.min().toInt(), elevations.max().toInt())
}

/** Top card of a tour shown on the map: name, date and the main figures. */
@Composable
fun TourInfoCard(tour: Tour, points: List<RoutePoint>, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val elevation = remember(points) { elevationFigures(points) }
    val hours = tour.durationSeconds / 3600.0
    Card(modifier = modifier) {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f).padding(start = 16.dp, top = 12.dp)) {
                Text(tour.name, style = MaterialTheme.typography.titleMedium)
                Text(subtitle(tour), style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Clear, contentDescription = "Tour ausblenden")
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row {
                Figure("Strecke", String.format(Locale.GERMANY, "%.1f km", tour.distanceMeters / 1000.0))
                Figure(
                    "Dauer",
                    if (tour.durationSeconds > 0) String.format(
                        Locale.GERMANY, "%d:%02d h", tour.durationSeconds / 3600, (tour.durationSeconds % 3600) / 60,
                    ) else "–",
                )
                Figure(
                    "Ø Tempo",
                    if (hours > 0) String.format(Locale.GERMANY, "%.1f km/h", tour.distanceMeters / 1000.0 / hours)
                    else "–",
                )
            }
            Row {
                Figure("Bergauf", "${tour.elevationGainMeters.toInt()} m")
                Figure("Bergab", elevation?.let { "${it.descentMeters} m" } ?: "–")
                Figure("Höhe", elevation?.let { "${it.minMeters}–${it.maxMeters} m" } ?: "–")
            }
        }
    }
}

@Composable
private fun RowScope.Figure(label: String, value: String) {
    Column(modifier = Modifier.weight(1f)) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "Wandern · Mo., 19.10.2026 · 08:12–09:40" (end time only when known). */
private fun subtitle(tour: Tour): String {
    val kind = if (tour.type == TourType.BIKE) "Rad" else "Wandern"
    val day = SimpleDateFormat("EE, dd.MM.yyyy", Locale.GERMANY).format(Date(tour.startedAt))
    val time = SimpleDateFormat("HH:mm", Locale.GERMANY)
    val span = time.format(Date(tour.startedAt)) +
        (tour.finishedAt?.let { "–" + time.format(Date(it)) } ?: "")
    return "$kind · $day · $span"
}
