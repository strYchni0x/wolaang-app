package de.wolaang.app.ui.plan

import android.location.Location
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import de.wolaang.app.routing.RoutePoint
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** One sample of the profile: distance from the start and height above sea level. */
data class ProfileSample(val distanceMeters: Double, val elevation: Double, val point: RoutePoint)

/** Cumulative distance along the route paired with elevation; points without height are skipped. */
fun buildProfile(points: List<RoutePoint>): List<ProfileSample> {
    val result = ArrayList<ProfileSample>(points.size)
    val dist = FloatArray(1)
    var total = 0.0
    points.forEachIndexed { i, p ->
        if (i > 0) {
            val prev = points[i - 1]
            Location.distanceBetween(prev.lat, prev.lon, p.lat, p.lon, dist)
            total += dist[0]
        }
        p.elevation?.let { result.add(ProfileSample(total, it, p)) }
    }
    return result
}

// Flat routes would otherwise turn 2 m of noise into a dramatic mountain range.
private const val MIN_ELEVATION_SPAN_M = 40.0

/**
 * Elevation profile as a single-series area chart. Touching/dragging shows a crosshair with
 * distance + height, and reports the touched sample via [onScrub] (null when released),
 * so the map can mark the same spot.
 */
@Composable
fun ElevationProfileCard(
    samples: List<ProfileSample>,
    onScrub: (ProfileSample?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (samples.size < 2) return
    var scrubbed by remember(samples) { mutableStateOf<ProfileSample?>(null) }

    val lineColor = MaterialTheme.colorScheme.primary
    val fillColor = lineColor.copy(alpha = 0.18f)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val crosshairColor = MaterialTheme.colorScheme.onSurface
    val surfaceColor = MaterialTheme.colorScheme.surface
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = labelColor)
    val textMeasurer = rememberTextMeasurer()

    val totalDistance = samples.last().distanceMeters
    val rawMin = samples.minOf { it.elevation }
    val rawMax = samples.maxOf { it.elevation }
    val pad = max(0.0, (MIN_ELEVATION_SPAN_M - (rawMax - rawMin)) / 2)
    val yMin = rawMin - pad
    val yMax = rawMax + pad

    fun sampleAt(x: Float, width: Float): ProfileSample {
        val d = (x / width).coerceIn(0f, 1f) * totalDistance
        return samples.minBy { kotlin.math.abs(it.distanceMeters - d) }
    }

    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            val s = scrubbed
            Text(
                if (s == null) {
                    String.format(Locale.GERMANY, "Höhenprofil · %d–%d m", rawMin.roundToInt(), rawMax.roundToInt())
                } else {
                    String.format(Locale.GERMANY, "%.1f km · %d m", s.distanceMeters / 1000, s.elevation.roundToInt())
                },
                style = MaterialTheme.typography.labelLarge,
            )
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .padding(top = 4.dp)
                    .pointerInput(samples) {
                        // Touch down shows the crosshair, moving the finger scrubs, lifting clears it.
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            down.consume()
                            fun show(x: Float) {
                                val hit = sampleAt(x, size.width.toFloat())
                                scrubbed = hit
                                onScrub(hit)
                            }
                            show(down.position.x)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                change.consume()
                                show(change.position.x)
                            }
                            scrubbed = null
                            onScrub(null)
                        }
                    },
            ) {
                // Reserve room for the axis labels: heights on the left, distances below.
                val maxLabel = textMeasurer.measure("${yMax.roundToInt()} m", labelStyle)
                val left = maxLabel.size.width + 6.dp.toPx()
                val bottomLabelHeight = maxLabel.size.height.toFloat()
                val plotW = size.width - left
                val plotH = size.height - bottomLabelHeight - 4.dp.toPx()

                fun xOf(d: Double) = left + (d / totalDistance * plotW).toFloat()
                fun yOf(e: Double) = ((yMax - e) / (yMax - yMin) * plotH).toFloat()

                // Recessive grid: top and bottom only, labelled with the visible range.
                listOf(yMax, yMin).forEach { e ->
                    val y = yOf(e)
                    drawLine(gridColor, Offset(left, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                    val label = textMeasurer.measure("${e.roundToInt()} m", labelStyle)
                    drawText(label, topLeft = Offset(0f, (y - label.size.height / 2f).coerceIn(0f, plotH - label.size.height)))
                }
                val startLabel = textMeasurer.measure("0 km", labelStyle)
                drawText(startLabel, topLeft = Offset(left, size.height - bottomLabelHeight))
                val endLabel = textMeasurer.measure(
                    String.format(Locale.GERMANY, "%.1f km", totalDistance / 1000), labelStyle,
                )
                drawText(endLabel, topLeft = Offset(size.width - endLabel.size.width, size.height - bottomLabelHeight))

                val line = Path().apply {
                    samples.forEachIndexed { i, p ->
                        if (i == 0) moveTo(xOf(p.distanceMeters), yOf(p.elevation))
                        else lineTo(xOf(p.distanceMeters), yOf(p.elevation))
                    }
                }
                val area = Path().apply {
                    addPath(line)
                    lineTo(xOf(totalDistance), plotH)
                    lineTo(left, plotH)
                    close()
                }
                drawPath(area, fillColor)
                drawPath(
                    line, lineColor,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )

                scrubbed?.let { p ->
                    val x = xOf(p.distanceMeters)
                    val y = yOf(p.elevation)
                    drawLine(crosshairColor, Offset(x, 0f), Offset(x, plotH), strokeWidth = 1.dp.toPx())
                    drawCircle(surfaceColor, radius = 6.dp.toPx(), center = Offset(x, y))
                    drawCircle(lineColor, radius = 4.dp.toPx(), center = Offset(x, y))
                }
            }
        }
    }
}
