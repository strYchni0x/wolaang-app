package de.wolaang.app.ui.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.wolaang.app.WolaangApp
import de.wolaang.app.data.GpxReader
import de.wolaang.app.data.MapSource
import de.wolaang.app.data.model.Tour
import de.wolaang.app.data.model.TourType
import de.wolaang.app.routing.RoutePoint
import de.wolaang.app.ui.record.RECORD_BUTTON_CLEARANCE
import de.wolaang.app.ui.plan.ElevationProfileCard
import de.wolaang.app.ui.plan.ProfileSample
import de.wolaang.app.ui.plan.WaypointIcons
import de.wolaang.app.ui.plan.buildProfile
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay

// Roughly the geographic center of Germany — used until we get a GPS fix.
private val GERMANY_CENTER = GeoPoint(51.1657, 10.4515)

// Line color of a shown recorded tour (distinct from the red live track and blue plans).
private val TOUR_COLOR = Color.parseColor("#7B1FA2")

// Zoom level from which the overview shows a start dot per tour.
private const val OVERVIEW_DOT_MIN_ZOOM = 13.0

// Accuracy (meters) at or below which we consider the fix "good" and hide the status hint.
private const val GOOD_ACCURACY_M = 20f

/** Most recent last-known location across GPS and network providers, or null. */
internal fun bestLastKnownLocation(lm: LocationManager): Location? =
    listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .filter { it in lm.allProviders }
        .mapNotNull { provider ->
            try {
                lm.getLastKnownLocation(provider)
            } catch (e: SecurityException) {
                null
            }
        }
        .maxByOrNull { it.time }

@Composable
fun MapScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val container = remember { (context.applicationContext as WolaangApp).container }
    val recorder = container.trackRecorder
    val recordingState by recorder.state.collectAsStateWithLifecycle()

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasLocationPermission = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            hasLocationPermission
    }

    // Latest fix used for the GPS status hint (separate from the map marker overlay).
    var currentLocation by remember { mutableStateOf<Location?>(null) }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            // Tile scaling (label size) is applied by useMapSource below.
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

    val locationOverlay = remember {
        MyLocationNewOverlay(GpsMyLocationProvider(context), mapView)
    }

    val trackPolyline = remember {
        Polyline().apply {
            outlinePaint.color = Color.parseColor("#D32F2F")
            outlinePaint.strokeWidth = 12f
        }
    }

    // A recorded tour opened from the Tours tab, shown as a purple line with start/end pins.
    var shownTour by remember { mutableStateOf<Tour?>(null) }
    var shownPoints by remember { mutableStateOf<List<RoutePoint>>(emptyList()) }
    var scrubSample by remember { mutableStateOf<ProfileSample?>(null) }
    val tourPolyline = remember {
        Polyline().apply {
            outlinePaint.color = TOUR_COLOR
            outlinePaint.strokeWidth = 12f
        }
    }
    val tourMarkers = remember { mutableListOf<Marker>() }

    // Overview of all recorded tours (or only hikes / bike tours), opened from the Tours tab.
    var overviewFilter by remember { mutableStateOf<OverviewFilter?>(null) }
    var overviewLines by remember { mutableStateOf<List<OverviewLine>>(emptyList()) }
    var overviewLoading by remember { mutableStateOf(false) }
    // Lines plus a start dot per tour; the dots only from OVERVIEW_DOT_MIN_ZOOM on, so
    // hundreds of tours don't clutter the zoomed-out map.
    val overviewOverlays = remember { mutableListOf<Overlay>() }
    DisposableEffect(mapView) {
        val listener = object : MapListener {
            override fun onScroll(event: ScrollEvent?) = false
            override fun onZoom(event: ZoomEvent?): Boolean {
                val show = mapView.zoomLevelDouble >= OVERVIEW_DOT_MIN_ZOOM
                overviewOverlays.filterIsInstance<Marker>()
                    .filter { it.isEnabled != show }
                    .forEach { it.isEnabled = show }
                return false
            }
        }
        mapView.addMapListener(listener)
        onDispose { mapView.removeMapListener(listener) }
    }
    val scope = rememberCoroutineScope()

    fun showTour(tour: Tour) {
        locationOverlay.disableFollowLocation()
        tourPolyline.outlinePaint.color = overviewColor(tour.type)
        shownPoints = GpxReader.readTrack(tour.gpxData)
        shownTour = tour
    }
    val scrubMarker = remember {
        Marker(mapView).apply {
            icon = WaypointIcons.dot(context, TOUR_COLOR)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setOnMarkerClickListener { _, _ -> true }
        }
    }

    // Ask for the permissions we need on first entry.
    LaunchedEffect(Unit) {
        val needed = buildList {
            if (!hasLocationPermission) add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    // Enable the location marker, jump to the last known position immediately,
    // and keep a live fix (GPS + fast coarse NETWORK) for the status display.
    DisposableEffect(hasLocationPermission) {
        var listener: LocationListener? = null
        var lm: LocationManager? = null

        // A tour requested from the Tours tab is loaded right after this effect; don't let
        // follow-location or the last-known position pull the map away from it meanwhile.
        val tourRequested = shownTour != null || container.tourToShow.value != null ||
            overviewFilter != null || container.toursOverview.value != null
        if (hasLocationPermission) {
            locationOverlay.enableMyLocation()
            if (!tourRequested) locationOverlay.enableFollowLocation()
            if (locationOverlay !in mapView.overlays) {
                mapView.overlays.add(locationOverlay)
            }

            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm = manager

            // (1) Show the last known position right away so we don't sit at the
            // center of Germany while waiting for the first fresh fix.
            bestLastKnownLocation(manager)?.let { last ->
                currentLocation = last
                if (!tourRequested) {
                    mapView.controller.setZoom(14.0)
                    mapView.controller.setCenter(GeoPoint(last.latitude, last.longitude))
                }
            }

            // (2)+(3) Live fixes for the accuracy hint. NETWORK_PROVIDER yields a
            // fast coarse position (cell/wifi) while real GPS is still acquiring.
            val l = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    currentLocation = location
                }

                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}

                @Deprecated("Kept for API < 29 where this method is abstract")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                }
            }
            listener = l
            try {
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, l)
                if (LocationManager.NETWORK_PROVIDER in manager.allProviders) {
                    manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1000L, 0f, l)
                }
            } catch (e: SecurityException) {
                // Permission revoked between check and request — ignore.
            }

            // Zoom in once we have a precise GPS fix.
            locationOverlay.runOnFirstFix {
                locationOverlay.myLocation?.takeIf { !tourRequested && shownTour == null && overviewFilter == null }?.let { fix ->
                    mapView.post {
                        mapView.controller.setZoom(16.0)
                        mapView.controller.animateTo(fix)
                    }
                }
            }
        }

        onDispose {
            locationOverlay.disableMyLocation()
            listener?.let { l -> lm?.removeUpdates(l) }
        }
    }

    // Show a recorded tour when the Tours tab asks for it.
    LaunchedEffect(Unit) {
        container.tourToShow.collect { id ->
            if (id == null) return@collect
            container.tourToShow.value = null
            val tour = container.tourDao.getTourById(id) ?: return@collect
            overviewFilter = null
            showTour(tour)
        }
    }

    // Show the overview when the Tours tab asks for it.
    LaunchedEffect(Unit) {
        container.toursOverview.collect { filter ->
            if (filter == null) return@collect
            container.toursOverview.value = null
            locationOverlay.disableFollowLocation()
            shownTour = null
            shownPoints = emptyList()
            overviewFilter = filter
        }
    }

    LaunchedEffect(overviewFilter) {
        val filter = overviewFilter
        if (filter == null) {
            overviewLines = emptyList()
            return@LaunchedEffect
        }
        overviewLoading = true
        overviewLines = withContext(Dispatchers.Default) {
            buildOverviewLines(container.tourDao.getAllTours().first(), filter)
        }
        overviewLoading = false
        val all = overviewLines.flatMap { it.points }
        if (all.size >= 2 && shownTour == null) {
            val box = BoundingBox.fromGeoPointsSafe(all)
            // Leave room for the overview card on top.
            val padded = BoundingBox(
                box.latNorth + box.latitudeSpan * 0.25,
                // extra room on the right for the zoom slider
                box.lonEast + box.longitudeSpanWithDateLine * 0.15,
                box.latSouth - box.latitudeSpan * 0.1,
                box.lonWest - box.longitudeSpanWithDateLine * 0.05,
            )
            mapView.zoomToBoxWhenReady(padded)
        }
    }

    LaunchedEffect(overviewLines) {
        mapView.overlays.removeAll(overviewOverlays)
        overviewOverlays.clear()
        val lines = mutableListOf<Overlay>()
        val dots = mutableListOf<Overlay>()
        overviewLines.forEach { line ->
            val select = {
                scope.launch {
                    // The overview holds thinned points; load the full track for the details.
                    val tour = container.tourDao.getTourById(line.tour.id) ?: line.tour
                    showTour(tour)
                }
            }
            val color = overviewColor(line.tour.type)
            lines.add(Polyline().apply {
                setPoints(line.points)
                outlinePaint.color = color
                outlinePaint.alpha = 0xC0
                outlinePaint.strokeWidth = 9f
                // Thin lines are hard to hit; widen the tap tolerance.
                setDensityMultiplier(4f)
                setOnClickListener { _, _, _ -> select(); true }
            })
            dots.add(Marker(mapView).apply {
                position = line.points.first()
                icon = WaypointIcons.dot(context, color)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                setOnMarkerClickListener { _, _ -> select(); true }
                isEnabled = mapView.zoomLevelDouble >= OVERVIEW_DOT_MIN_ZOOM
            })
        }
        overviewOverlays.addAll(lines + dots)
        // Below everything else (selected tour, live track, location marker).
        mapView.overlays.addAll(0, overviewOverlays)
        mapView.invalidate()
    }

    LaunchedEffect(shownPoints) {
        mapView.overlays.remove(tourPolyline)
        mapView.overlays.removeAll(tourMarkers)
        tourMarkers.clear()
        val points = shownPoints.map { GeoPoint(it.lat, it.lon) }
        if (points.size >= 2) {
            tourPolyline.setPoints(points)
            // Above the overview lines, below markers and the live track.
            mapView.overlays.add(overviewOverlays.size, tourPolyline)
            listOf(0 to points.first(), 1 to points.last()).forEach { (i, p) ->
                tourMarkers.add(Marker(mapView).apply {
                    position = p
                    icon = WaypointIcons.forIndex(context, i, 2)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    setOnMarkerClickListener { _, _ -> true }
                })
            }
            mapView.overlays.addAll(tourMarkers)
            // Leave room for the info card on top and the profile + button below.
            val box = BoundingBox.fromGeoPointsSafe(points)
            val padded = BoundingBox(
                box.latNorth + box.latitudeSpan * 0.4,
                box.lonEast + box.longitudeSpanWithDateLine * 0.1,
                box.latSouth - box.latitudeSpan * 1.3,
                box.lonWest - box.longitudeSpanWithDateLine * 0.1,
            )
            mapView.zoomToBoxWhenReady(padded)
        }
        mapView.invalidate()
    }

    LaunchedEffect(scrubSample) {
        mapView.overlays.remove(scrubMarker)
        scrubSample?.let {
            scrubMarker.position = GeoPoint(it.point.lat, it.point.lon)
            mapView.overlays.add(scrubMarker)
        }
        mapView.invalidate()
    }

    // Redraw the live track polyline whenever the recorded points change.
    LaunchedEffect(recordingState.points) {
        trackPolyline.setPoints(recordingState.points.map { GeoPoint(it.lat, it.lon) })
        if (trackPolyline !in mapView.overlays) {
            mapView.overlays.add(0, trackPolyline) // below the location marker
        }
        mapView.invalidate()
    }

    // Forward Compose lifecycle events to the MapView.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    mapView.onResume()
                    // The permission may have been granted meanwhile (e.g. via the record button).
                    hasLocationPermission = hasLocationPermission || ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_FINE_LOCATION,
                    ) == PackageManager.PERMISSION_GRANTED
                }
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
        AndroidViewMap(mapView)
        MapZoomSlider(mapView, Modifier.align(Alignment.CenterEnd).padding(end = 8.dp))

        if (recordingState.isRecording) {
            StatsCard(
                type = recordingState.type,
                distanceMeters = recordingState.distanceMeters,
                elevationGainMeters = recordingState.elevationGainMeters,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(12.dp)
                    .fillMaxWidth(),
            )
        } else if (shownTour != null) {
            TourInfoCard(
                tour = shownTour!!,
                onClose = {
                    shownTour = null
                    shownPoints = emptyList()
                    scrubSample = null
                    // Back to the overview if the tour was picked there; otherwise to the user.
                    if (overviewFilter == null && hasLocationPermission) {
                        locationOverlay.enableFollowLocation()
                        locationOverlay.myLocation?.let { mapView.controller.animateTo(it) }
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(12.dp)
                    .fillMaxWidth(),
            )
        } else if (overviewFilter != null) {
            OverviewCard(
                filter = overviewFilter!!,
                lines = overviewLines,
                loading = overviewLoading,
                onFilter = { overviewFilter = it },
                onClose = {
                    overviewFilter = null
                    if (hasLocationPermission) {
                        locationOverlay.enableFollowLocation()
                        locationOverlay.myLocation?.let { mapView.controller.animateTo(it) }
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(12.dp)
                    .fillMaxWidth(),
            )
        } else {
            // Show a "searching / improving" hint until the fix is accurate enough.
            val accuracy = currentLocation?.accuracy
            if (currentLocation == null || (accuracy != null && accuracy > GOOD_ACCURACY_M)) {
                GpsStatusCard(
                    accuracyMeters = accuracy,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(12.dp)
                        .fillMaxWidth(),
                )
            }
        }

        if (shownTour != null) {
            val profile = remember(shownPoints) { buildProfile(shownPoints) }
            ElevationProfileCard(
                samples = profile,
                onScrub = { scrubSample = it },
                lineColor = ComposeColor(TOUR_COLOR),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    // Leaves room for the record button that sticks out of the bottom bar.
                    .padding(start = 12.dp, end = 12.dp, bottom = RECORD_BUTTON_CLEARANCE)
                    .fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun AndroidViewMap(mapView: MapView) {
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { mapView },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun TourInfoCard(tour: Tour, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).padding(start = 16.dp, top = 12.dp, bottom = 12.dp)) {
                Text(tour.name, style = MaterialTheme.typography.titleMedium)
                val kind = if (tour.type == TourType.BIKE) "Rad" else "Wandern"
                Text(
                    String.format(
                        Locale.GERMANY,
                        "%s · %.1f km · %d:%02d h · %d m ↑",
                        kind,
                        tour.distanceMeters / 1000.0,
                        tour.durationSeconds / 3600,
                        (tour.durationSeconds % 3600) / 60,
                        tour.elevationGainMeters.toInt(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Clear, contentDescription = "Tour ausblenden")
            }
        }
    }
}

@Composable
private fun GpsStatusCard(accuracyMeters: Float?, modifier: Modifier = Modifier) {
    val text = if (accuracyMeters == null) {
        "GPS wird gesucht…"
    } else {
        String.format(Locale.GERMANY, "GPS wird genauer… (±%d m)", accuracyMeters.toInt())
    }
    Card(modifier = modifier) {
        Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        )
    }
}

@Composable
private fun StatsCard(
    type: TourType,
    distanceMeters: Float,
    elevationGainMeters: Float,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Text(if (type == TourType.BIKE) "Rad" else "Wandern")
            Text(String.format(Locale.GERMANY, "%.2f km", distanceMeters / 1000.0))
            Text(String.format(Locale.GERMANY, "%d m ↑", elevationGainMeters.toInt()))
        }
    }
}
