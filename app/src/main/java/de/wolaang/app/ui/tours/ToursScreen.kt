package de.wolaang.app.ui.tours

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.wolaang.app.WolaangApp
import de.wolaang.app.data.GpxShare
import de.wolaang.app.data.ImportOptions
import de.wolaang.app.data.ImportResult
import de.wolaang.app.data.ImportTarget
import de.wolaang.app.data.model.TourType
import de.wolaang.app.ui.map.OverviewFilter
import de.wolaang.app.ui.map.OverviewRequest
import de.wolaang.app.ui.map.countryCodes
import de.wolaang.app.ui.map.yearOf
import de.wolaang.app.ui.settings.RadioOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private val DATE_FORMAT = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY)

/** One row in the list — planned routes and recorded tours share the same layout. */
private data class ListEntry(
    val key: String,
    val name: String,
    val details: String,
    val date: Long,
    val gpx: String,
    val delete: suspend () -> Unit,
    /** Opens the entry on the map (planned routes in the Plan tab, recorded tours in the Map tab). */
    val open: (() -> Unit)? = null,
)

@Composable
fun ToursScreen(onOpenPlan: () -> Unit, onOpenMap: () -> Unit) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as WolaangApp).container }
    val tours by container.tourDao.getAllTours().collectAsStateWithLifecycle(initialValue = emptyList())
    val planned by container.plannedRouteDao.getAllPlannedRoutes()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<ListEntry?>(null) }

    // List filter: activity type and year (planned routes by creation date).
    var typeFilter by rememberSaveable { mutableStateOf(OverviewFilter.ALL) }
    var yearFilter by rememberSaveable { mutableStateOf<Int?>(null) }
    var countryFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val years = remember(tours, planned) {
        (tours.map { yearOf(it.startedAt) } + planned.map { yearOf(it.createdAt) }).distinct().sortedDescending()
    }
    // Countries present, as (code, German name), sorted by name.
    val countries = remember(tours, planned) {
        (tours.flatMap { countryCodes(it.countries) } + planned.flatMap { countryCodes(it.countries) })
            .distinct()
            .map { it to container.countryLookup.nameOf(it) }
            .sortedBy { it.second }
    }
    fun matches(type: TourType, date: Long, countries: String) =
        typeFilter.matches(type) && (yearFilter == null || yearOf(date) == yearFilter) &&
            (countryFilter == null || countryFilter in countryCodes(countries))

    // GPX import: pick files → choose target/type → import → show the result.
    var filesToImport by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var importing by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf<ImportResult?>(null) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        filesToImport = uris
    }

    val plannedEntries = planned.filter { matches(it.type, it.createdAt, it.countries) }.map { r ->
        ListEntry(
            key = "p${r.id}",
            name = r.name,
            details = summary(r.type, r.distanceMeters, r.estimatedDurationSeconds, r.ascentMeters),
            date = r.createdAt,
            gpx = r.gpxData,
            delete = { container.plannedRouteDao.deletePlannedRoute(r.id) },
            open = {
                container.routeToOpen.value = r.id
                onOpenPlan()
            },
        )
    }
    val tourEntries = tours.filter { matches(it.type, it.startedAt, it.countries) }.map { t ->
        ListEntry(
            key = "t${t.id}",
            name = t.name,
            details = summary(t.type, t.distanceMeters, t.durationSeconds, t.elevationGainMeters.toInt()),
            date = t.startedAt,
            gpx = t.gpxData,
            delete = { container.tourDao.deleteTour(t.id) },
            open = {
                container.tourToShow.value = t.id
                onOpenMap()
            },
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (tours.isEmpty() && planned.isEmpty()) {
            Text(
                "Noch keine Touren geplant oder aufgezeichnet.\nGPX-Dateien, z. B. aus Komoot, lassen sich importieren.",
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            // Keep the last card clear of the import button.
            contentPadding = PaddingValues(bottom = 88.dp),
        ) {
            if (tours.isNotEmpty() || planned.isNotEmpty()) {
                item {
                    FilterRow(
                        typeFilter = typeFilter,
                        onType = { typeFilter = it },
                        years = years,
                        year = yearFilter,
                        onYear = { yearFilter = it },
                        countries = countries,
                        country = countryFilter,
                        onCountry = { countryFilter = it },
                    )
                }
                if (plannedEntries.isEmpty() && tourEntries.isEmpty()) {
                    item {
                        Text(
                            "Keine Touren für diese Auswahl.",
                            modifier = Modifier.padding(vertical = 24.dp).fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            if (plannedEntries.isNotEmpty()) {
                item { SectionHeader("Geplante Routen") }
                items(plannedEntries, key = { it.key }) { entry ->
                    EntryCard(entry, onShare = { GpxShare.share(context, entry.name, entry.gpx) }) {
                        pendingDelete = entry
                    }
                }
            }
            if (tourEntries.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.weight(1f)) { SectionHeader("Aufgezeichnete Touren") }
                        TextButton(
                            // Shows the tours of the current filter.
                            onClick = {
                                container.toursOverview.value = OverviewRequest(typeFilter, yearFilter, countryFilter)
                                onOpenMap()
                            },
                            modifier = Modifier.padding(top = 12.dp),
                        ) { Text("Auf der Karte zeigen") }
                    }
                }
                items(tourEntries, key = { it.key }) { entry ->
                    EntryCard(entry, onShare = { GpxShare.share(context, entry.name, entry.gpx) }) {
                        pendingDelete = entry
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            // GPX has no reliable MIME type across file managers, so accept any file.
            onClick = { pickFiles.launch(arrayOf("*/*")) },
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            text = { Text("GPX importieren") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }

    if (filesToImport.isNotEmpty()) {
        val defaultTarget by container.settings.importTarget.collectAsStateWithLifecycle()
        ImportDialog(
            fileCount = filesToImport.size,
            defaultTarget = defaultTarget,
            onDismiss = { filesToImport = emptyList() },
            onImport = { options ->
                val uris = filesToImport
                filesToImport = emptyList()
                importing = true
                scope.launch {
                    importResult = container.gpxImporter.import(uris, options)
                    importing = false
                }
            },
        )
    }

    if (importing) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Import läuft …") },
            text = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) },
            confirmButton = {},
        )
    }

    importResult?.let { result ->
        AlertDialog(
            onDismissRequest = { importResult = null },
            title = { Text(if (result.imported == 1) "1 Tour importiert" else "${result.imported} Touren importiert") },
            text = if (result.failed.isEmpty()) null else {
                {
                    Column {
                        Text("Nicht importiert:")
                        result.failed.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { importResult = null }) { Text("OK") } },
        )
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Löschen?") },
            text = { Text("„${entry.name}“ wird endgültig gelöscht.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch { entry.delete() }
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Abbrechen") } },
        )
    }
}

/** Chips for the activity type plus year and country menus. */
@Composable
private fun FilterRow(
    typeFilter: OverviewFilter,
    onType: (OverviewFilter) -> Unit,
    years: List<Int>,
    year: Int?,
    onYear: (Int?) -> Unit,
    countries: List<Pair<String, String>>,
    country: String?,
    onCountry: (String?) -> Unit,
) {
    var yearMenu by remember { mutableStateOf(false) }
    var countryMenu by remember { mutableStateOf(false) }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
    ) {
        OverviewFilter.entries.forEach { f ->
            FilterChip(selected = f == typeFilter, onClick = { onType(f) }, label = { Text(f.label) })
        }
        Box {
            FilterChip(
                selected = year != null,
                onClick = { yearMenu = true },
                label = { Text(year?.toString() ?: "Jahr") },
                trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
            )
            DropdownMenu(expanded = yearMenu, onDismissRequest = { yearMenu = false }) {
                DropdownMenuItem(text = { Text("Alle Jahre") }, onClick = { onYear(null); yearMenu = false })
                years.forEach { y ->
                    DropdownMenuItem(text = { Text(y.toString()) }, onClick = { onYear(y); yearMenu = false })
                }
            }
        }
        Box {
            FilterChip(
                selected = country != null,
                onClick = { countryMenu = true },
                label = { Text(countries.firstOrNull { it.first == country }?.second ?: "Land") },
                trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
            )
            DropdownMenu(expanded = countryMenu, onDismissRequest = { countryMenu = false }) {
                DropdownMenuItem(text = { Text("Alle Länder") }, onClick = { onCountry(null); countryMenu = false })
                countries.forEach { (code, name) ->
                    DropdownMenuItem(text = { Text(name) }, onClick = { onCountry(code); countryMenu = false })
                }
            }
        }
    }
}

/** Choose where the picked files go (default from the settings) and the activity type. */
@Composable
private fun ImportDialog(
    fileCount: Int,
    defaultTarget: ImportTarget,
    onDismiss: () -> Unit,
    onImport: (ImportOptions) -> Unit,
) {
    var target by remember(defaultTarget) { mutableStateOf(defaultTarget) }
    // null = guess per file (speed for recorded tracks, name otherwise)
    var type by remember { mutableStateOf<TourType?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (fileCount == 1) "1 Datei importieren" else "$fileCount Dateien importieren") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("Importieren als", style = MaterialTheme.typography.labelLarge)
                Column(modifier = Modifier.selectableGroup()) {
                    RadioOption("Geplante Route", target == ImportTarget.PLANNED) { target = ImportTarget.PLANNED }
                    RadioOption("Aufgezeichnete Tour", target == ImportTarget.RECORDED) { target = ImportTarget.RECORDED }
                }
                Text("Art", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                Column(modifier = Modifier.selectableGroup()) {
                    RadioOption("Automatisch erkennen", type == null) { type = null }
                    RadioOption("Wandern", type == TourType.HIKE) { type = TourType.HIKE }
                    RadioOption("Rad", type == TourType.BIKE) { type = TourType.BIKE }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onImport(ImportOptions(target, type)) }) { Text("Importieren") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

private fun summary(type: TourType, distanceMeters: Float, durationSeconds: Long, ascentMeters: Int): String {
    val kind = if (type == TourType.BIKE) "Rad" else "Wandern"
    val km = String.format(Locale.GERMANY, "%.1f km", distanceMeters / 1000.0)
    val time = String.format(Locale.GERMANY, "%d:%02d h", durationSeconds / 3600, (durationSeconds % 3600) / 60)
    return "$kind · $km · $time · $ascentMeters m ↑"
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun EntryCard(entry: ListEntry, onShare: () -> Unit, onDelete: () -> Unit) {
    val cardModifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 6.dp)
    val content: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f).padding(16.dp)) {
                Text(entry.name, style = MaterialTheme.typography.titleMedium)
                Text(entry.details, style = MaterialTheme.typography.bodyMedium)
                Text(DATE_FORMAT.format(Date(entry.date)), style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Default.Share, contentDescription = "Als GPX teilen")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Löschen")
            }
        }
    }
    val open = entry.open
    if (open != null) {
        Card(onClick = open, modifier = cardModifier) { content() }
    } else {
        Card(modifier = cardModifier) { content() }
    }
}
