package de.wolaang.app.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val NEEDLE_RED = Color(0xFFD32F2F)

/**
 * Switches between north up and direction of travel up. The needle always points north,
 * so it turns with the map; the button is highlighted while the map turns with you.
 */
@Composable
fun CompassButton(mapOrientation: Float, headingUp: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    MapRoundButton(
        onClick = onClick,
        highlighted = headingUp,
        description = if (headingUp) "Karte nach Norden ausrichten" else "Karte in Bewegungsrichtung ausrichten",
        modifier = modifier,
    ) {
        val other = MaterialTheme.colorScheme.onSurface
        Canvas(Modifier.size(26.dp)) {
            rotate(mapOrientation) {
                val cx = size.width / 2; val cy = size.height / 2
                val half = size.width * 0.18f
                val north = Path().apply {
                    moveTo(cx, 0f); lineTo(cx + half, cy); lineTo(cx - half, cy); close()
                }
                val south = Path().apply {
                    moveTo(cx, size.height); lineTo(cx + half, cy); lineTo(cx - half, cy); close()
                }
                drawPath(north, NEEDLE_RED)
                drawPath(south, other.copy(alpha = 0.6f))
            }
        }
    }
}

/** Centers the map on the current position and follows it again. */
@Composable
fun LocateButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    MapRoundButton(onClick = onClick, highlighted = false, description = "Zu meinem Standort", modifier = modifier) {
        val color = MaterialTheme.colorScheme.onSurface
        Canvas(Modifier.size(24.dp)) {
            val c = Offset(size.width / 2, size.height / 2)
            val r = size.width * 0.3f
            val w = 2.dp.toPx()
            drawCircle(color, radius = r, center = c, style = Stroke(w))
            drawCircle(color, radius = r * 0.4f, center = c)
            // Crosshair ticks outside the ring.
            listOf(Offset(0f, -1f), Offset(0f, 1f), Offset(-1f, 0f), Offset(1f, 0f)).forEach { d ->
                drawLine(color, c + d * r, c + d * (size.width / 2), strokeWidth = w)
            }
        }
    }
}

@Composable
private fun MapRoundButton(
    onClick: () -> Unit,
    highlighted: Boolean,
    description: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (highlighted) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        shadowElevation = 3.dp,
        modifier = modifier.size(44.dp).semantics { contentDescription = description },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(9.dp),
        ) { content() }
    }
}
