package de.wolaang.app.ui.tours

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.wolaang.app.WolaangApp
import de.wolaang.app.data.model.Tour
import de.wolaang.app.data.model.TourType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DATE_FORMAT = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY)

@Composable
fun ToursScreen() {
    val context = LocalContext.current
    val tourDao = remember { (context.applicationContext as WolaangApp).container.tourDao }
    val tours by tourDao.getAllTours().collectAsStateWithLifecycle(initialValue = emptyList())

    if (tours.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Noch keine Touren aufgezeichnet")
        }
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        items(tours, key = { it.id }) { tour ->
            TourCard(tour)
        }
    }
}

@Composable
private fun TourCard(tour: Tour) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(tour.name, style = MaterialTheme.typography.titleMedium)
            val type = if (tour.type == TourType.BIKE) "Radtour" else "Wanderung"
            val km = String.format(Locale.GERMANY, "%.2f km", tour.distanceMeters / 1000.0)
            val minutes = tour.durationSeconds / 60
            Text(
                "$type · $km · $minutes min · ${tour.elevationGainMeters.toInt()} m ↑",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                DATE_FORMAT.format(Date(tour.startedAt)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
