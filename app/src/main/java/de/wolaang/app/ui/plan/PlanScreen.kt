package de.wolaang.app.ui.plan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.wolaang.app.WolaangApp
import de.wolaang.app.data.GpxShare
import de.wolaang.app.data.GpxWriter
import de.wolaang.app.data.model.PlannedRoute
import de.wolaang.app.data.model.TourType
import de.wolaang.app.routing.RouteResult
import de.wolaang.app.ui.map.bestLastKnownLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val GERMANY_CENTER = GeoPoint(51.1657, 10.4515)
private val NAME_DATE_FORMAT = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY)

private fun defaultRouteName(type: TourType): String =
    (if (type == TourType.BIKE) "Radtour " else "Wanderung ") + NAME_DATE_FORMAT.format(Date())

/**
 * Route planning: tap the map to add waypoints (start, optional via points, destination).
 * As soon as there are two points the route is computed offline with BRouter.
 */
@Composable
fun PlanScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val container = remember { (context.applicationContext as WolaangApp).container }
    val router = container.offlineRouter
    val scope = rememberCoroutineScope()

    val waypoints = remember { mutableStateListOf<GeoPoint>() }
    var tourType by remember { mutableStateOf(TourType.HIKE) }
    var route by remember { mutableStateOf<RouteResult?>(null) }
    var isRouting by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var missingSegments by remember { mutableStateOf<List<String>>(emptyList()) }
    var downloadProgress by remember { mutableStateOf<Float?>(null) }
    // Bumped after a tile download so routing is retried.
    var segmentsVersion by remember { mutableIntStateOf(0) }
    var showSaveDialog by remember { mutableStateOf(false) }
    // True once the current route has been saved (reset when the route changes).
    var isSaved by remember { mutableStateOf(false) }
    // Index of the waypoint the user tapped (to delete it), or null.
    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setTilesScaledToDpi(true)
            setTilesScaleFactor(1.4f)
            setMultiTouchControls(true)
            controller.setZoom(6.0)
            controller.setCenter(GERMANY_CENTER)
        }
    }

    val routePolyline = remember {
        Polyline().apply {
            outlinePaint.color = Color.parseColor("#1565C0")
            outlinePaint.strokeWidth = 12f
        }
    }

    // Start near the user, if we already know where they are.
    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            bestLastKnownLocation(lm)?.let {
                mapView.controller.setZoom(14.0)
                mapView.controller.setCenter(GeoPoint(it.latitude, it.longitude))
            }
        }
    }

    // Taps on the map add waypoints (or just clear the current selection).
    DisposableEffect(Unit) {
        val tapOverlay = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                if (selectedIndex != null) selectedIndex = null else waypoints.add(p)
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean = false
        })
        mapView.overlays.add(0, tapOverlay)
        onDispose { mapView.overlays.remove(tapOverlay) }
    }

    // Draw waypoint markers. Long-press + drag moves a point, a tap selects it.
    LaunchedEffect(waypoints.toList(), selectedIndex) {
        mapView.overlays.removeAll { it is Marker }
        waypoints.forEachIndexed { i, p ->
            mapView.overlays.add(Marker(mapView).apply {
                position = p
                icon = WaypointIcons.forIndex(context, i, waypoints.size)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                title = pointLabel(i, waypoints.size)
                alpha = if (selectedIndex == null || selectedIndex == i) 1f else 0.4f
                isDraggable = true
                setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
                    override fun onMarkerDragStart(marker: Marker) {
                        selectedIndex = null
                    }

                    override fun onMarkerDrag(marker: Marker) {}

                    override fun onMarkerDragEnd(marker: Marker) {
                        if (i < waypoints.size) waypoints[i] = marker.position
                    }
                })
                setOnMarkerClickListener { _, _ ->
                    selectedIndex = if (selectedIndex == i) null else i
                    true // no info bubble
                }
            })
        }
        mapView.invalidate()
    }

    // (Re)compute the route whenever waypoints, profile or available tiles change.
    LaunchedEffect(waypoints.toList(), tourType, segmentsVersion) {
        errorText = null
        route = null
        isSaved = false
        val pts = waypoints.map { it.latitude to it.longitude }
        if (pts.size < 2) {
            missingSegments = emptyList()
            return@LaunchedEffect
        }
        missingSegments = router.missingSegments(pts)
        if (missingSegments.isNotEmpty()) return@LaunchedEffect

        isRouting = true
        try {
            route = router.route(pts, tourType)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorText = e.message ?: e.toString()
        } finally {
            isRouting = false
        }
    }

    // Draw the computed route.
    LaunchedEffect(route) {
        val r = route
        if (r == null) {
            mapView.overlays.remove(routePolyline)
        } else {
            routePolyline.setPoints(r.points.map { GeoPoint(it.lat, it.lon) })
            if (routePolyline !in mapView.overlays) mapView.overlays.add(1, routePolyline)
        }
        mapView.invalidate()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        Card(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(12.dp)
                .fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = tourType == TourType.HIKE,
                        onClick = { tourType = TourType.HIKE },
                        label = { Text("Wandern") },
                    )
                    FilterChip(
                        selected = tourType == TourType.BIKE,
                        onClick = { tourType = TourType.BIKE },
                        label = { Text("Rad") },
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (waypoints.isNotEmpty()) {
                        IconButton(onClick = {
                            selectedIndex = null
                            waypoints.clear()
                        }) {
                            Icon(Icons.Default.Clear, contentDescription = "Route löschen")
                        }
                    }
                }

                val progress = downloadProgress
                val selected = selectedIndex?.takeIf { it < waypoints.size }
                when {
                    selected != null -> {
                        Text(
                            "${pointLabel(selected, waypoints.size)} ausgewählt",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                selectedIndex = null
                                waypoints.removeAt(selected)
                            }) { Text("Punkt löschen") }
                            OutlinedButton(onClick = { selectedIndex = null }) { Text("Fertig") }
                        }
                    }
                    progress != null -> {
                        Text("Routing-Daten werden geladen… ${(progress * 100).toInt()} %")
                        Spacer(modifier = Modifier.height(4.dp))
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    }
                    missingSegments.isNotEmpty() -> {
                        errorText?.let { Text("Fehler: $it", color = MaterialTheme.colorScheme.error) }
                        Text(
                            "Für diese Region fehlen Routing-Daten (${missingSegments.joinToString()}, " +
                                "je ca. 50–200 MB). Einmal herunterladen, danach geht das Planen offline."
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = {
                            scope.launch {
                                try {
                                    for ((i, name) in missingSegments.withIndex()) {
                                        router.downloadSegment(name) { p ->
                                            downloadProgress = (i + p) / missingSegments.size
                                        }
                                    }
                                    segmentsVersion++
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    errorText = e.message ?: e.toString()
                                } finally {
                                    downloadProgress = null
                                }
                            }
                        }) { Text("Herunterladen") }
                    }
                    isRouting -> Text("Route wird berechnet…")
                    errorText != null -> Text(
                        "Fehler: $errorText",
                        color = MaterialTheme.colorScheme.error,
                    )
                    route != null -> {
                        RouteSummary(route!!)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { showSaveDialog = true }, enabled = !isSaved) {
                                Text(if (isSaved) "Gespeichert" else "Speichern")
                            }
                            OutlinedButton(onClick = {
                                val name = defaultRouteName(tourType)
                                GpxShare.share(context, name, GpxWriter.writeRoute(name, route!!.points))
                            }) { Text("GPX teilen") }
                        }
                    }
                    waypoints.isEmpty() -> Text("Tippe auf die Karte, um den Start zu setzen.")
                    else -> Text("Tippe auf die Karte, um das Ziel zu setzen.")
                }
            }
        }
    }

    val currentRoute = route
    if (showSaveDialog && currentRoute != null) {
        SaveRouteDialog(
            initialName = defaultRouteName(tourType),
            onDismiss = { showSaveDialog = false },
            onSave = { name ->
                showSaveDialog = false
                val waypointsJson = waypoints.joinToString(",", "[", "]") { "[${it.latitude},${it.longitude}]" }
                scope.launch {
                    container.plannedRouteDao.insertPlannedRoute(
                        PlannedRoute(
                            name = name,
                            type = tourType,
                            createdAt = System.currentTimeMillis(),
                            waypointsJson = waypointsJson,
                            distanceMeters = currentRoute.distanceMeters.toFloat(),
                            ascentMeters = currentRoute.ascentMeters,
                            estimatedDurationSeconds = currentRoute.durationSeconds.toLong(),
                            gpxData = GpxWriter.writeRoute(name, currentRoute.points),
                        )
                    )
                    isSaved = true
                }
            },
        )
    }
}

private fun pointLabel(index: Int, count: Int): String = when (index) {
    0 -> "Start"
    count - 1 -> "Ziel"
    else -> "Zwischenpunkt $index"
}

@Composable
private fun SaveRouteDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Route speichern") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim()) }, enabled = name.isNotBlank()) {
                Text("Speichern")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable
private fun RouteSummary(route: RouteResult) {
    val h = route.durationSeconds / 3600
    val m = (route.durationSeconds % 3600) / 60
    Text(
        String.format(
            Locale.GERMANY,
            "%.1f km  ·  %d m ↑  ·  %d:%02d h",
            route.distanceMeters / 1000.0,
            route.ascentMeters,
            h,
            m,
        ),
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        "Tippen fügt Punkte hinzu · Punkt lange drücken zum Verschieben, antippen zum Löschen",
        style = MaterialTheme.typography.bodySmall,
    )
}
