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
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.wolaang.app.WolaangApp
import de.wolaang.app.data.GpxReader
import de.wolaang.app.data.GpxShare
import de.wolaang.app.data.GpxWriter
import de.wolaang.app.data.MapSource
import de.wolaang.app.data.model.PlannedRoute
import de.wolaang.app.data.model.TourType
import de.wolaang.app.routing.RouteResult
import de.wolaang.app.ui.common.rememberMeteredDownloadGuard
import de.wolaang.app.ui.map.MapZoomSlider
import de.wolaang.app.ui.map.bestLastKnownLocation
import de.wolaang.app.ui.map.setDarkTiles
import de.wolaang.app.ui.map.useMapSource
import de.wolaang.app.ui.record.RECORD_BUTTON_CLEARANCE
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

private val GERMANY_CENTER = GeoPoint(51.1657, 10.4515)
private val ROUTE_COLOR = Color.parseColor("#1565C0")
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
    val downloadGuard = rememberMeteredDownloadGuard()
    var showSaveDialog by remember { mutableStateOf(false) }
    // The saved route currently being edited (opened from the Tours tab), or null.
    var editingRoute by remember { mutableStateOf<PlannedRoute?>(null) }
    // Waypoints + type as last saved/loaded; the plan counts as saved while it matches.
    var savedState by remember { mutableStateOf<Pair<List<GeoPoint>, TourType>?>(null) }
    val isSaved = savedState == (waypoints.toList() to tourType)
    // Fit the map to the next computed route (after opening a saved one).
    var zoomToRoute by remember { mutableStateOf(false) }
    // Index of the waypoint the user tapped (to delete it), or null.
    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(6.0)
            controller.setCenter(GERMANY_CENTER)
        }
    }

    // Darken the map tiles together with the app's dark design.
    val darkMap = MaterialTheme.colorScheme.background.luminance() < 0.5f
    LaunchedEffect(darkMap) { mapView.setDarkTiles(darkMap) }

    // Online OSM tiles or the downloaded offline maps, as chosen in the settings.
    val mapSource by container.settings.mapSource.collectAsStateWithLifecycle()
    val installedMaps by container.offlineMaps.installed.collectAsStateWithLifecycle()
    val mapTextSize by container.settings.mapTextSize.collectAsStateWithLifecycle()
    LaunchedEffect(mapSource, installedMaps, mapTextSize) {
        val files = if (mapSource == MapSource.OFFLINE) installedMaps.map { it.file } else emptyList()
        mapView.useMapSource(files, darkMap, mapTextSize)
    }
    // OSM data attribution (ODbL), for online tiles and offline maps alike.
    DisposableEffect(Unit) {
        val copyright = CopyrightOverlay(context)
        mapView.overlays.add(copyright)
        onDispose { mapView.overlays.remove(copyright) }
    }

    // Marks the spot the finger is on in the elevation profile.
    val scrubMarker = remember {
        Marker(mapView).apply {
            icon = WaypointIcons.dot(context, ROUTE_COLOR)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setOnMarkerClickListener { _, _ -> true }
        }
    }
    var scrubSample by remember { mutableStateOf<ProfileSample?>(null) }

    val routePolyline = remember {
        Polyline().apply {
            outlinePaint.color = ROUTE_COLOR
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
        mapView.overlays.removeAll { it is Marker && it !== scrubMarker }
        waypoints.forEachIndexed { i, p ->
            mapView.overlays.add(Marker(mapView).apply {
                position = p
                icon = WaypointIcons.forIndex(context, i, waypoints.size)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) // pin tip = point
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
        val pts = waypoints.map { it.latitude to it.longitude }
        if (pts.size < 2) {
            missingSegments = emptyList()
            return@LaunchedEffect
        }
        // An opened saved route shows its stored line (exact for imports, no tiles needed)
        // until the user changes points or profile.
        val editing = editingRoute
        if (editing != null && savedState == (waypoints.toList() to tourType)) {
            missingSegments = emptyList()
            route = RouteResult(
                points = GpxReader.readTrack(editing.gpxData),
                distanceMeters = editing.distanceMeters.toInt(),
                ascentMeters = editing.ascentMeters,
                durationSeconds = editing.estimatedDurationSeconds.toInt(),
            )
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

    // Open a saved route when the Tours tab asks for it.
    LaunchedEffect(Unit) {
        container.routeToOpen.collect { id ->
            if (id == null) return@collect
            container.routeToOpen.value = null
            val saved = container.plannedRouteDao.getPlannedRouteById(id) ?: return@collect
            val points = parseWaypoints(saved.waypointsJson)
            selectedIndex = null
            tourType = saved.type
            waypoints.clear()
            waypoints.addAll(points)
            editingRoute = saved
            savedState = points to saved.type
            zoomToRoute = true
        }
    }

    LaunchedEffect(scrubSample) {
        val s = scrubSample
        mapView.overlays.remove(scrubMarker)
        if (s != null) {
            scrubMarker.position = GeoPoint(s.point.lat, s.point.lon)
            // Above the route line but below the waypoint pins, so it never hides their labels.
            val pinIndex = mapView.overlays.indexOfFirst { it is Marker }
            if (pinIndex >= 0) mapView.overlays.add(pinIndex, scrubMarker) else mapView.overlays.add(scrubMarker)
        }
        mapView.invalidate()
    }

    // Draw the computed route.
    LaunchedEffect(route) {
        val r = route
        if (r == null) {
            mapView.overlays.remove(routePolyline)
        } else {
            val points = r.points.map { GeoPoint(it.lat, it.lon) }
            routePolyline.setPoints(points)
            if (routePolyline !in mapView.overlays) mapView.overlays.add(1, routePolyline)
            if (zoomToRoute) {
                zoomToRoute = false
                // The info card covers roughly the top third of the map and the elevation
                // profile the bottom fifth, so extend the box to keep the route between them.
                val box = BoundingBox.fromGeoPointsSafe(points + waypoints)
                val latSpan = box.latitudeSpan
                val lonSpan = box.longitudeSpanWithDateLine
                val padded = BoundingBox(
                    box.latNorth + latSpan * 0.7,
                    box.lonEast + lonSpan * 0.1,
                    box.latSouth - latSpan * 0.5,
                    box.lonWest - lonSpan * 0.1,
                )
                // Post, so the MapView has its final size.
                mapView.post { mapView.zoomToBoundingBox(padded, false, 60) }
            }
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
        MapZoomSlider(mapView, Modifier.align(Alignment.CenterEnd).padding(end = 8.dp))

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
                            editingRoute = null
                            savedState = null
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
                            downloadGuard("ca. ${missingSegments.size * 180} MB") {
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
                            }
                        }) { Text("Herunterladen") }
                    }
                    isRouting -> Text("Route wird berechnet…")
                    errorText != null -> Text(
                        "Fehler: $errorText",
                        color = MaterialTheme.colorScheme.error,
                    )
                    route != null -> {
                        editingRoute?.let {
                            Text(it.name, style = MaterialTheme.typography.labelLarge)
                        }
                        RouteSummary(route!!)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { showSaveDialog = true }, enabled = !isSaved) {
                                Text(if (isSaved) "Gespeichert" else "Speichern")
                            }
                            OutlinedButton(onClick = {
                                val name = editingRoute?.name ?: defaultRouteName(tourType)
                                GpxShare.share(context, name, GpxWriter.writeRoute(name, route!!.points))
                            }) { Text("GPX teilen") }
                        }
                    }
                    waypoints.isEmpty() -> Text("Tippe auf die Karte, um den Start zu setzen.")
                    else -> Text("Tippe auf die Karte, um das Ziel zu setzen.")
                }
            }
        }
        val profile = remember(route) { route?.let { buildProfile(it.points) } ?: emptyList() }
        if (route != null && selectedIndex == null) {
            ElevationProfileCard(
                samples = profile,
                onScrub = { scrubSample = it },
                lineColor = ComposeColor(ROUTE_COLOR),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 12.dp, end = 12.dp, bottom = RECORD_BUTTON_CLEARANCE)
                    .fillMaxWidth(),
            )
        }
    }

    val currentRoute = route
    if (showSaveDialog && currentRoute != null) {
        val editing = editingRoute
        SaveRouteDialog(
            initialName = editing?.name ?: defaultRouteName(tourType),
            canOverwrite = editing != null,
            onDismiss = { showSaveDialog = false },
            onSave = { name, asNew ->
                showSaveDialog = false
                val snapshot = waypoints.toList() to tourType
                val waypointsJson = snapshot.first.joinToString(",", "[", "]") { "[${it.latitude},${it.longitude}]" }
                val overwrite = editing != null && !asNew
                val data = PlannedRoute(
                    id = if (overwrite) editing!!.id else 0,
                    name = name,
                    type = tourType,
                    createdAt = if (overwrite) editing!!.createdAt else System.currentTimeMillis(),
                    waypointsJson = waypointsJson,
                    distanceMeters = currentRoute.distanceMeters.toFloat(),
                    ascentMeters = currentRoute.ascentMeters,
                    estimatedDurationSeconds = currentRoute.durationSeconds.toLong(),
                    gpxData = GpxWriter.writeRoute(name, currentRoute.points),
                    notes = editing?.notes ?: "",
                )
                scope.launch {
                    editingRoute = if (overwrite) {
                        container.plannedRouteDao.updatePlannedRoute(data)
                        data
                    } else {
                        data.copy(id = container.plannedRouteDao.insertPlannedRoute(data))
                    }
                    savedState = snapshot
                }
            },
        )
    }
}

/** Parses the stored "[[lat,lon],…]" waypoint list. */
private fun parseWaypoints(json: String): List<GeoPoint> {
    val array = JSONArray(json)
    return (0 until array.length()).map { i ->
        val pair = array.getJSONArray(i)
        GeoPoint(pair.getDouble(0), pair.getDouble(1))
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
    canOverwrite: Boolean,
    onDismiss: () -> Unit,
    onSave: (name: String, asNew: Boolean) -> Unit,
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
            if (canOverwrite) {
                // Three actions don't fit side by side — stack them, right-aligned.
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { onSave(name.trim(), false) }, enabled = name.isNotBlank()) {
                        Text("Überschreiben")
                    }
                    TextButton(onClick = { onSave(name.trim(), true) }, enabled = name.isNotBlank()) {
                        Text("Als neue Route speichern")
                    }
                    TextButton(onClick = onDismiss) { Text("Abbrechen") }
                }
            } else {
                TextButton(onClick = { onSave(name.trim(), false) }, enabled = name.isNotBlank()) {
                    Text("Speichern")
                }
            }
        },
        dismissButton = if (canOverwrite) null else {
            { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
        },
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
