package de.wolaang.app.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.wolaang.app.data.model.TourType
import de.wolaang.app.navigation.NavigationProgress
import de.wolaang.app.navigation.TurnKind
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

// Speeds for the remaining time, as in planning.
private const val HIKE_SPEED_KMH = 4.0
private const val BIKE_SPEED_KMH = 16.0

/**
 * Top card while navigating: the next turn with its distance, the remaining distance and
 * the estimated arrival; off the route, how far away it is.
 */
@Composable
fun NavigationCard(
    routeName: String,
    type: TourType,
    progress: NavigationProgress?,
    // e.g. "Aufzeichnung 3,2 km · 0:45 h" while recording at the same time
    recordingLine: String?,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val offRoute = progress?.isOffRoute == true
    Card(
        modifier = modifier,
        colors = if (offRoute) CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ) else CardDefaults.cardColors(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
            val kind = progress?.next?.kind
            if (progress != null && !offRoute) {
                val color = MaterialTheme.colorScheme.primary
                Canvas(Modifier.size(56.dp)) {
                    if (kind == TurnKind.ARRIVE || progress.hasArrived) drawFinish(color)
                    else drawTurn(progress.next.angle, color)
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f).padding(vertical = 10.dp)) {
                when {
                    progress == null -> Text("Warte auf GPS…", style = MaterialTheme.typography.titleMedium)
                    progress.hasArrived -> Text("Ziel erreicht", style = MaterialTheme.typography.headlineSmall)
                    offRoute -> {
                        Text("Abseits der Route", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${formatDistance(progress.offRouteMeters)} bis zur Route – der gestrichelten Linie folgen",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    else -> {
                        Text(
                            formatDistance(progress.toNextMeters),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            if (kind == TurnKind.ARRIVE) "bis zum Ziel" else "dann ${kind?.label}",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                if (progress != null && !progress.hasArrived) {
                    val speed = if (type == TourType.BIKE) BIKE_SPEED_KMH else HIKE_SPEED_KMH
                    val seconds = (progress.remainingMeters / 1000.0 / speed * 3600).toLong()
                    val arrival = SimpleDateFormat("HH:mm", Locale.GERMANY)
                        .format(Date(System.currentTimeMillis() + seconds * 1000))
                    Text(
                        String.format(
                            Locale.GERMANY, "Noch %.1f km · %d:%02d h · Ankunft ca. %s",
                            progress.remainingMeters / 1000.0, seconds / 3600, (seconds % 3600) / 60, arrival,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    recordingLine ?: routeName,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
            IconButton(onClick = onStop) {
                Icon(Icons.Default.Clear, contentDescription = "Navigation beenden")
            }
        }
    }
}

private fun formatDistance(meters: Double): String = when {
    meters < 1000 -> "${(meters / 10).toInt() * 10} m"
    else -> String.format(Locale.GERMANY, "%.1f km", meters / 1000.0)
}

/** Arrow from the bottom center up, bending by [angle] degrees (negative = left). */
private fun DrawScope.drawTurn(angle: Float, color: Color) {
    val w = size.width
    val stroke = Stroke(width = w * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val bend = Offset(w / 2, w * 0.55f)
    val rad = Math.toRadians(angle.toDouble())
    // Heading 0 = up; positive angles turn clockwise (right).
    val dir = Offset(sin(rad).toFloat(), -cos(rad).toFloat())
    val tip = bend + dir * (w * 0.4f)
    val path = Path().apply {
        moveTo(w / 2, w * 0.95f)
        lineTo(bend.x, bend.y)
        lineTo(tip.x, tip.y)
    }
    drawPath(path, color, style = stroke)
    // Arrow head.
    val back = dir * (-w * 0.18f)
    val side = Offset(-dir.y, dir.x) * (w * 0.14f)
    val head = Path().apply {
        moveTo(tip.x + dir.x * w * 0.06f, tip.y + dir.y * w * 0.06f)
        lineTo(tip.x + back.x + side.x, tip.y + back.y + side.y)
        lineTo(tip.x + back.x - side.x, tip.y + back.y - side.y)
        close()
    }
    drawPath(head, color)
}

/** Target symbol for the arrival. */
private fun DrawScope.drawFinish(color: Color) {
    val c = Offset(size.width / 2, size.height / 2)
    drawCircle(color, radius = size.width * 0.4f, center = c, style = Stroke(size.width * 0.1f))
    drawCircle(color, radius = size.width * 0.15f, center = c)
}
