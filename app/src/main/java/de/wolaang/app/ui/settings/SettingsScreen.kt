package de.wolaang.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.wolaang.app.WolaangApp
import de.wolaang.app.data.ImportTarget
import de.wolaang.app.data.ThemeMode
import de.wolaang.app.routing.OfflineRouter
import de.wolaang.app.routing.SegmentInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val DATE_FORMAT = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY)

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as WolaangApp).container }
    val settings = container.settings
    val themeMode by settings.themeMode.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        SectionTitle("Design")
        Column(modifier = Modifier.selectableGroup()) {
            RadioOption("Wie System", themeMode == ThemeMode.SYSTEM) { settings.setThemeMode(ThemeMode.SYSTEM) }
            RadioOption("Hell", themeMode == ThemeMode.LIGHT) { settings.setThemeMode(ThemeMode.LIGHT) }
            RadioOption("Dunkel", themeMode == ThemeMode.DARK) { settings.setThemeMode(ThemeMode.DARK) }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        val importTarget by settings.importTarget.collectAsStateWithLifecycle()
        SectionTitle("GPX-Import")
        Text(
            "Standard-Ziel für importierte Dateien (beim Import änderbar):",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.selectableGroup()) {
            RadioOption("Geplante Routen", importTarget == ImportTarget.PLANNED) {
                settings.setImportTarget(ImportTarget.PLANNED)
            }
            RadioOption("Aufgezeichnete Touren (z. B. Übernahme aus Komoot)", importTarget == ImportTarget.RECORDED) {
                settings.setImportTarget(ImportTarget.RECORDED)
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        OfflineMapsSection(container.offlineMaps, settings)

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

        RoutingRegions(container.offlineRouter)
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
internal fun RadioOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, modifier = Modifier.padding(start = 12.dp))
    }
}

/**
 * Downloaded BRouter tiles: size, version date, delete, and an explicit update check.
 * Network is only used when the user taps "Auf Updates prüfen" or "Aktualisieren".
 */
@Composable
private fun RoutingRegions(router: OfflineRouter) {
    val scope = rememberCoroutineScope()
    var segments by remember { mutableStateOf(router.downloadedSegments()) }
    // name → newer version date on the server
    val updates = remember { mutableStateMapOf<String, Long>() }
    // name → download progress 0..1
    val progress = remember { mutableStateMapOf<String, Float>() }
    var checking by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<SegmentInfo?>(null) }

    fun refresh() {
        segments = router.downloadedSegments()
    }

    SectionTitle("Routing-Regionen")
    if (segments.isEmpty()) {
        Text(
            "Noch keine Regionen auf dem Gerät. Beim Planen wird der Download automatisch angeboten.",
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }

    Text(
        "Belegt: ${formatSize(segments.sumOf { it.sizeBytes })}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.height(4.dp))

    segments.forEach { seg ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(OfflineRouter.describeSegment(seg.name), style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${seg.name} · ${formatSize(seg.sizeBytes)} · Stand ${DATE_FORMAT.format(Date(seg.lastModified))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val p = progress[seg.name]
                if (p != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                }
            }
            val newer = updates[seg.name]
            if (newer != null && progress[seg.name] == null) {
                TextButton(onClick = {
                    scope.launch {
                        progress[seg.name] = 0f
                        try {
                            router.downloadSegment(seg.name) { progress[seg.name] = it }
                            updates.remove(seg.name)
                            statusText = "${seg.name} aktualisiert."
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            statusText = "Fehler: ${e.message ?: e}"
                        } finally {
                            progress.remove(seg.name)
                            refresh()
                        }
                    }
                }) { Text("Aktualisieren") }
            }
            IconButton(onClick = { pendingDelete = seg }, enabled = progress[seg.name] == null) {
                Icon(Icons.Default.Delete, contentDescription = "Region löschen")
            }
        }
    }

    Spacer(modifier = Modifier.height(8.dp))
    OutlinedButton(
        enabled = !checking,
        onClick = {
            scope.launch {
                checking = true
                statusText = null
                refresh()
                try {
                    var found = 0
                    for (seg in segments) {
                        val remote = router.remoteLastModified(seg.name) ?: continue
                        // A day of tolerance: same build, different timestamps on mirror/disk.
                        if (remote > seg.lastModified + 24 * 3600_000L) {
                            updates[seg.name] = remote
                            found++
                        } else {
                            updates.remove(seg.name)
                        }
                    }
                    statusText = when (found) {
                        0 -> "Alle Regionen sind aktuell."
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
    ) { Text(if (checking) "Wird geprüft…" else "Auf Updates prüfen") }

    statusText?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    }

    pendingDelete?.let { seg ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Region löschen?") },
            text = {
                Text(
                    "${OfflineRouter.describeSegment(seg.name)} (${formatSize(seg.sizeBytes)}) wird gelöscht. " +
                        "Zum Planen in dieser Gegend muss sie neu geladen werden."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    router.deleteSegment(seg.name)
                    updates.remove(seg.name)
                    pendingDelete = null
                    refresh()
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Abbrechen") } },
        )
    }
}

private fun formatSize(bytes: Long): String =
    String.format(Locale.GERMANY, "%.0f MB", bytes / (1024.0 * 1024.0))
