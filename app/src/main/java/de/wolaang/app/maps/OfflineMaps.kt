package de.wolaang.app.maps

import android.content.Context
import de.wolaang.app.data.FileDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** A region offered for download (Mapsforge vector map of one German state). */
data class MapRegion(val id: String, val label: String) {
    val url get() = "$BASE_URL$id.map"

    private companion object {
        const val BASE_URL = "https://download.mapsforge.org/maps/v5/europe/germany/"
    }
}

/** A map file on the device; [lastModified] is the server's version date. */
data class InstalledMap(val region: MapRegion, val file: File, val sizeBytes: Long, val lastModified: Long)

/**
 * Offline vector maps from download.mapsforge.org (OSM data, ODbL), stored in
 * filesDir/maps and rendered on the device — unlike the online OSM tiles, these may be
 * downloaded for whole regions.
 */
class OfflineMaps(
    context: Context,
    private val downloader: FileDownloader,
    private val onFirstMapInstalled: () -> Unit,
) {

    private val dir = File(context.applicationContext.filesDir, "maps")

    private val _installed = MutableStateFlow(scan())
    val installed: StateFlow<List<InstalledMap>> = _installed.asStateFlow()

    // Downloads run app-wide, so they continue when the user leaves the settings screen.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _downloads = MutableStateFlow<Map<String, Float>>(emptyMap())
    /** Running downloads: region id → progress 0..1. */
    val downloads: StateFlow<Map<String, Float>> = _downloads.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    /** Result of the last download ("… geladen." / "Fehler: …"). */
    val message: StateFlow<String?> = _message.asStateFlow()

    fun startDownload(region: MapRegion) {
        if (region.id in _downloads.value) return
        _downloads.update { it + (region.id to 0f) }
        _message.value = null
        scope.launch {
            val wasEmpty = _installed.value.isEmpty()
            try {
                var lastPercent = -1
                downloader.download(region.url, fileOf(region)) { p ->
                    // Only whole percents, so a 500 MB file doesn't trigger thousands of UI updates.
                    val percent = (p * 100).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        _downloads.update { it + (region.id to p) }
                    }
                }
                _message.value = "${region.label} geladen."
                if (wasEmpty) onFirstMapInstalled()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = "Fehler bei ${region.label}: ${e.message ?: e}"
            } finally {
                _downloads.update { it - region.id }
                refresh()
            }
        }
    }

    fun delete(region: MapRegion) {
        fileOf(region).delete()
        refresh()
    }

    suspend fun remoteLastModified(region: MapRegion): Long? = downloader.remoteLastModified(region.url)

    private fun fileOf(region: MapRegion) = File(dir, "${region.id}.map")

    private fun refresh() {
        _installed.value = scan()
    }

    private fun scan(): List<InstalledMap> = REGIONS.mapNotNull { region ->
        fileOf(region).takeIf { it.isFile }?.let { InstalledMap(region, it, it.length(), it.lastModified()) }
    }

    companion object {
        val REGIONS = listOf(
            MapRegion("baden-wuerttemberg", "Baden-Württemberg"),
            MapRegion("bayern", "Bayern"),
            MapRegion("berlin", "Berlin"),
            MapRegion("brandenburg", "Brandenburg"),
            MapRegion("bremen", "Bremen"),
            MapRegion("hamburg", "Hamburg"),
            MapRegion("hessen", "Hessen"),
            MapRegion("mecklenburg-vorpommern", "Mecklenburg-Vorpommern"),
            MapRegion("niedersachsen", "Niedersachsen"),
            MapRegion("nordrhein-westfalen", "Nordrhein-Westfalen"),
            MapRegion("rheinland-pfalz", "Rheinland-Pfalz"),
            MapRegion("saarland", "Saarland"),
            MapRegion("sachsen", "Sachsen"),
            MapRegion("sachsen-anhalt", "Sachsen-Anhalt"),
            MapRegion("schleswig-holstein", "Schleswig-Holstein"),
            MapRegion("thueringen", "Thüringen"),
        )
    }
}
