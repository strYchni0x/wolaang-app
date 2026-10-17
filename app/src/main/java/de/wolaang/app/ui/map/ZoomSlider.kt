package de.wolaang.app.ui.map

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView

const val MIN_MAP_ZOOM = 4.0
const val MAX_MAP_ZOOM = 20.0

/**
 * Vertical zoom slider for the map's right edge (zoom in at the top), replacing osmdroid's
 * +/- buttons. Follows pinch zoom and programmatic zoom changes as well.
 */
@Composable
fun MapZoomSlider(mapView: MapView, modifier: Modifier = Modifier) {
    var zoom by remember { mutableFloatStateOf(mapView.zoomLevelDouble.toFloat()) }

    DisposableEffect(mapView) {
        mapView.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        mapView.minZoomLevel = MIN_MAP_ZOOM
        mapView.maxZoomLevel = MAX_MAP_ZOOM
        zoom = mapView.zoomLevelDouble.toFloat()
        val listener = object : MapListener {
            override fun onScroll(event: ScrollEvent?) = false
            override fun onZoom(event: ZoomEvent?): Boolean {
                event?.let { zoom = it.zoomLevel.toFloat() }
                return false
            }
        }
        mapView.addMapListener(listener)
        onDispose { mapView.removeMapListener(listener) }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        shadowElevation = 3.dp,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 6.dp)) {
            Text("+", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = zoom.coerceIn(MIN_MAP_ZOOM.toFloat(), MAX_MAP_ZOOM.toFloat()),
                onValueChange = {
                    zoom = it
                    mapView.controller.setZoom(it.toDouble())
                },
                valueRange = MIN_MAP_ZOOM.toFloat()..MAX_MAP_ZOOM.toFloat(),
                modifier = Modifier
                    .semantics { contentDescription = "Zoom" }
                    .vertical()
                    .width(200.dp)
                    .height(44.dp),
            )
            Text("−", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Turns a horizontal component by 90° so its start is at the bottom and its end at the top. */
private fun Modifier.vertical() = this
    .layout { measurable, constraints ->
        // Measure with swapped axes, then report the rotated size.
        val placeable = measurable.measure(
            Constraints(
                minWidth = constraints.minHeight,
                maxWidth = constraints.maxHeight,
                minHeight = constraints.minWidth,
                maxHeight = constraints.maxWidth,
            )
        )
        layout(placeable.height, placeable.width) { placeable.place(0, placeable.width) }
    }
    .graphicsLayer {
        rotationZ = 270f
        transformOrigin = TransformOrigin(0f, 0f)
    }
