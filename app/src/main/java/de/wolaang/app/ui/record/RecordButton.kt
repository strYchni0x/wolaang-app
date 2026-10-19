package de.wolaang.app.ui.record

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.wolaang.app.WolaangApp
import de.wolaang.app.data.model.TourType
import de.wolaang.app.tracking.TrackingService

private val RECORDING_RED = Color(0xFFD32F2F)

/** How far the record button reaches up into the screen above the bar, plus a margin. */
val RECORD_BUTTON_CLEARANCE = 34.dp

/**
 * Round start/stop button raised out of the middle of the bottom bar, so a recording can be
 * started from every screen. Asks for the tour type before starting and confirms stopping.
 * [onStarted] runs once a recording has been started, [onStopped] once it has been stopped
 * (e.g. to show the map, where the finished tour appears).
 */
@Composable
fun RecordButton(onStarted: () -> Unit, onStopped: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as WolaangApp).container }
    val state by container.trackRecorder.state.collectAsStateWithLifecycle()
    var showTypeDialog by remember { mutableStateOf(false) }
    var showStopDialog by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) showTypeDialog = true
    }

    fun onClick() {
        when {
            state.isRecording -> showStopDialog = true
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED -> showTypeDialog = true
            else -> permissionLauncher.launch(
                buildList {
                    add(Manifest.permission.ACCESS_FINE_LOCATION)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }.toTypedArray()
            )
        }
    }

    // The ring in the bar's color sets the button off from the bar and the map behind it.
    Surface(
        onClick = ::onClick,
        shape = CircleShape,
        color = if (state.isRecording) RECORDING_RED else MaterialTheme.colorScheme.primary,
        contentColor = Color.White,
        border = BorderStroke(4.dp, NavigationBarDefaults.containerColor),
        shadowElevation = 6.dp,
        modifier = modifier
            .size(68.dp)
            .semantics { contentDescription = if (state.isRecording) "Aufzeichnung beenden" else "Aufzeichnen" },
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (state.isRecording) {
                // Stop symbol (the core icon set has none).
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(Color.White, RoundedCornerShape(3.dp))
                )
            } else {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(36.dp))
            }
        }
    }

    if (showTypeDialog) {
        RecordingTypeDialog(
            preselected = container.settings.lastRecordingType,
            onDismiss = { showTypeDialog = false },
            onStart = { type ->
                showTypeDialog = false
                container.settings.lastRecordingType = type
                TrackingService.start(context, type)
                onStarted()
            },
        )
    }

    if (showStopDialog) {
        AlertDialog(
            onDismissRequest = { showStopDialog = false },
            title = { Text("Aufzeichnung beenden?") },
            text = { Text("Die Tour wird gespeichert und erscheint im Touren-Tab.") },
            confirmButton = {
                TextButton(onClick = {
                    showStopDialog = false
                    TrackingService.stop(context)
                    onStopped()
                }) { Text("Beenden") }
            },
            dismissButton = { TextButton(onClick = { showStopDialog = false }) { Text("Weiter aufzeichnen") } },
        )
    }
}

/** Label under the raised button, sitting in the bar like the other tab labels. */
@Composable
fun RecordButtonLabel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as WolaangApp).container }
    val state by container.trackRecorder.state.collectAsStateWithLifecycle()
    Text(
        if (state.isRecording) "Stopp" else "Aufzeichnen",
        style = MaterialTheme.typography.labelMedium,
        color = if (state.isRecording) RECORDING_RED else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** Asks what is being recorded; the last choice is the highlighted (filled) button. */
@Composable
private fun RecordingTypeDialog(
    preselected: TourType,
    onDismiss: () -> Unit,
    onStart: (TourType) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aufzeichnung starten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(TourType.HIKE to "Wandern", TourType.BIKE to "Rad").forEach { (type, label) ->
                    if (type == preselected) {
                        Button(onClick = { onStart(type) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                    } else {
                        OutlinedButton(onClick = { onStart(type) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
