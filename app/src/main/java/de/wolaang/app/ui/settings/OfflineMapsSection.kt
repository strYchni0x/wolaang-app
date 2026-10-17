package de.wolaang.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.wolaang.app.data.AppSettings
import de.wolaang.app.data.MapSource
import de.wolaang.app.maps.InstalledMap
import de.wolaang.app.maps.MapRegion
import de.wolaang.app.maps.OfflineMaps
import de.wolaang.app.ui.common.rememberMeteredDownloadGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DATE_FORMAT = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY)

/**
 * Map source (online OSM tiles / offline Mapsforge maps) and the downloaded map regions.
 * Network is only used for an explicit download, update check or update.
 */
@Composable
internal fun OfflineMapsSection(maps: OfflineMaps, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    val installed by maps.installed.collectAsStateWithLifecycle()
    val mapSource by settings.mapSource.collectAsStateWithLifecycle()
    val progress by maps.downloads.collectAsStateWithLifecycle()
    val downloadMessage by maps.message.collectAsStateWithLifecycle()
    val updates = remember { mutableStateMapOf<String, Long>() }
    var checking by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<InstalledMap?>(null) }
    val downloadGuard = rememberMeteredDownloadGuard()

    fun download(region: MapRegion) {
        statusText = null
        updates.remove(region.id)
        maps.startDownload(region)
    }

    SectionTitle("Offline-Karten")
    Column(modifier = Modifier.selectableGroup()) {
        RadioOption("Online-Karte (OpenStreetMap)", mapSource == MapSource.ONLINE) {
            settings.setMapSource(MapSource.ONLINE)
        }
        RadioOption(
            if (installed.isEmpty()) "Offline-Karten (erst Region laden)" else "Offline-Karten",
            mapSource == MapSource.OFFLINE && installed.isNotEmpty(),
        ) {
            if (installed.isNotEmpty()) settings.setMapSource(MapSource.OFFLINE)
        }
    }
    Text(
        "Offline-Karten werden auf dem Handy gezeichnet und funktionieren ohne Netz. Außerhalb geladener " +
            "Regionen bleibt die Karte leer.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(8.dp))

    if (installed.isNotEmpty()) {
        Text(
            "Belegt: ${formatSize(installed.sumOf { it.sizeBytes })}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    installed.forEach { map ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(map.region.label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${formatSize(map.sizeBytes)} · Stand ${DATE_FORMAT.format(Date(map.lastModified))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                progress[map.region.id]?.let { p ->
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                }
            }
            if (updates.containsKey(map.region.id) && progress[map.region.id] == null) {
                TextButton(onClick = { downloadGuard(formatSize(map.sizeBytes)) { download(map.region) } }) { Text("Aktualisieren") }
            }
            IconButton(onClick = { pendingDelete = map }, enabled = progress[map.region.id] == null) {
                Icon(Icons.Default.Delete, contentDescription = "Karte löschen")
            }
        }
    }
    // Downloads of regions that are not installed yet.
    progress.filterKeys { id -> installed.none { it.region.id == id } }.forEach { (id, p) ->
        val label = OfflineMaps.REGIONS.first { it.id == id }.label
        Text("$label wird geladen … ${(p * 100).toInt()} %", style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
    }

    Spacer(modifier = Modifier.height(8.dp))
    Row {
        OutlinedButton(onClick = { showAddDialog = true }) { Text("Region hinzufügen") }
        if (installed.isNotEmpty()) {
            Spacer(modifier = Modifier.padding(start = 8.dp))
            OutlinedButton(
                enabled = !checking,
                onClick = {
                    scope.launch {
                        checking = true
                        statusText = null
                        try {
                            var found = 0
                            for (map in installed) {
                                val remote = maps.remoteLastModified(map.region) ?: continue
                                // A day of tolerance between server and file timestamps.
                                if (remote > map.lastModified + 24 * 3600_000L) {
                                    updates[map.region.id] = remote
                                    found++
                                }
                            }
                            statusText = when (found) {
                                0 -> "Alle Karten sind aktuell."
                                1 -> "1 Update verfügbar."
                                else -> "$found Updates verfügbar."
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            statusText = "Prüfen fehlgeschlagen: ${e.message ?: e}"
                        } finally {
                            checking = false
                        }
                    }
                },
            ) { Text(if (checking) "Wird geprüft…" else "Updates prüfen") }
        }
    }
    (statusText ?: downloadMessage)?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    }

    if (showAddDialog) {
        val available = OfflineMaps.REGIONS.filter { r -> installed.none { it.region.id == r.id } && r.id !in progress }
        var choice by remember { mutableStateOf<MapRegion?>(null) }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Region hinzufügen") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                        .selectableGroup(),
                ) {
                    Text(
                        "Karten von download.mapsforge.org (OpenStreetMap-Daten). Ein Bundesland ist " +
                            "etwa 15–600 MB groß, am besten im WLAN laden.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    available.forEach { region ->
                        RadioOption(region.label, choice == region) { choice = region }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = choice != null,
                    onClick = {
                        showAddDialog = false
                        choice?.let { region -> downloadGuard("bis zu 600 MB") { download(region) } }
                    },
                ) { Text("Herunterladen") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Abbrechen") } },
        )
    }

    pendingDelete?.let { map ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Karte löschen?") },
            text = { Text("${map.region.label} (${formatSize(map.sizeBytes)}) wird gelöscht.") },
            confirmButton = {
                TextButton(onClick = {
                    maps.delete(map.region)
                    updates.remove(map.region.id)
                    pendingDelete = null
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Abbrechen") } },
        )
    }
}

private fun formatSize(bytes: Long): String =
    String.format(Locale.GERMANY, "%.0f MB", bytes / (1024.0 * 1024.0))
