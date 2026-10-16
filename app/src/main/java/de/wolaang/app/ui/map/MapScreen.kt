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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.wolaang.app.WolaangApp
import de.wolaang.app.tracking.TrackingService
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.util.Locale

// Roughly the geographic center of Germany — used until we get a GPS fix.
private val GERMANY_CENTER = GeoPoint(51.1657, 10.4515)

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
    val recorder = remember { (context.applicationContext as WolaangApp).container.trackRecorder }
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
            // Scale raster tiles to the screen density so labels (street names)
            // are readable on high-DPI phones. tilesScaleFactor adds extra size
            // on top of that — increase it for even bigger labels (1.0 = DPI only).
            setTilesScaledToDpi(true)
            setTilesScaleFactor(1.4f)
            setMultiTouchControls(true)
            controller.setZoom(6.0)
            controller.setCenter(GERMANY_CENTER)
        }
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

        if (hasLocationPermission) {
            locationOverlay.enableMyLocation()
            locationOverlay.enableFollowLocation()
            if (locationOverlay !in mapView.overlays) {
                mapView.overlays.add(locationOverlay)
            }

            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm = manager

            // (1) Show the last known position right away so we don't sit at the
            // center of Germany while waiting for the first fresh fix.
            bestLastKnownLocation(manager)?.let { last ->
                currentLocation = last
                mapView.controller.setZoom(14.0)
                mapView.controller.setCenter(GeoPoint(last.latitude, last.longitude))
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
                locationOverlay.myLocation?.let { fix ->
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
        AndroidViewMap(mapView)

        if (recordingState.isRecording) {
            StatsCard(
                distanceMeters = recordingState.distanceMeters,
                elevationGainMeters = recordingState.elevationGainMeters,
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

        ExtendedFloatingActionButton(
            onClick = {
                if (recordingState.isRecording) {
                    TrackingService.stop(context)
                } else if (hasLocationPermission) {
                    TrackingService.start(context)
                } else {
                    permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
                }
            },
            icon = {
                Icon(
                    imageVector = if (recordingState.isRecording) Icons.Default.Clear else Icons.Default.PlayArrow,
                    contentDescription = null,
                )
            },
            text = { Text(if (recordingState.isRecording) "Stopp" else "Aufzeichnen") },
            containerColor = if (recordingState.isRecording) {
                ComposeColor(0xFFD32F2F)
            } else {
                MaterialTheme.colorScheme.primary
            },
            contentColor = ComposeColor.White,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        )
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
            Text(String.format(Locale.GERMANY, "%.2f km", distanceMeters / 1000.0))
            Text(String.format(Locale.GERMANY, "%d m ↑", elevationGainMeters.toInt()))
        }
    }
}
