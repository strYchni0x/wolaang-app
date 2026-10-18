package de.wolaang.app.data

import android.content.Context
import androidx.room.Room
import de.wolaang.app.data.db.PlannedRouteDao
import de.wolaang.app.data.db.TourDao
import de.wolaang.app.data.db.WolaangDatabase
import de.wolaang.app.data.db.gpxOf
import de.wolaang.app.maps.OfflineMaps
import de.wolaang.app.routing.OfflineRouter
import de.wolaang.app.tracking.TrackRecorder
import de.wolaang.app.ui.map.OverviewRequest
import de.wolaang.app.geo.CountryLookup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Simple manual dependency container.
 * Created once in [de.wolaang.app.WolaangApp] and accessed via the Application instance.
 */
class AppContainer(context: Context) {

    private val database: WolaangDatabase = Room.databaseBuilder(
        context.applicationContext,
        WolaangDatabase::class.java,
        "wolaang.db"
    ).addMigrations(
        WolaangDatabase.MIGRATION_1_2,
        WolaangDatabase.MIGRATION_2_3,
        WolaangDatabase.MIGRATION_3_4,
    ).build()

    val tourDao: TourDao = database.tourDao()
    val plannedRouteDao: PlannedRouteDao = database.plannedRouteDao()

    /** Shared live state of the current recording (written by TrackingService). */
    val trackRecorder: TrackRecorder = TrackRecorder()

    private val downloader = FileDownloader(userAgent = context.packageName)

    /** Offline route planning (BRouter). */
    val offlineRouter: OfflineRouter = OfflineRouter(context, downloader)

    /** Offline vector maps (Mapsforge) per region. */
    val offlineMaps: OfflineMaps = OfflineMaps(context, downloader) {
        // The first downloaded map is what the user wants to see — switch to it.
        settings.setMapSource(MapSource.OFFLINE)
    }

    /** GPX import into planned routes or recorded tours. */
    val gpxImporter: GpxImporter = GpxImporter(context.contentResolver, tourDao, plannedRouteDao)

    /** Offline country borders (for the country filter in the Tours tab). */
    val countryLookup: CountryLookup = CountryLookup(context.applicationContext)

    init {
        // Determine the countries of new, imported or (after the v3 update) existing entries
        // in the background, whichever way they were saved.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope.launch {
            tourDao.getUntaggedTourIds().collect { ids ->
                ids.forEach { tourDao.setCountries(it, countriesOf(tourDao.gpxOf(it))) }
            }
        }
        scope.launch {
            plannedRouteDao.getUntaggedRouteIds().collect { ids ->
                ids.forEach { plannedRouteDao.setCountries(it, countriesOf(plannedRouteDao.gpxOf(it))) }
            }
        }
    }

    private fun countriesOf(gpx: String): String {
        val track = runCatching { GpxReader.readTrack(gpx) }.getOrDefault(emptyList())
        return countryLookup.countriesOf(track).joinToString(",").ifEmpty { "-" }
    }

    /** User preferences (theme, …). */
    val settings: AppSettings = AppSettings(context)

    /** Id of a saved planned route the Plan tab should open next (set by the Tours tab). */
    val routeToOpen = MutableStateFlow<Long?>(null)

    /** Id of a recorded tour the Map tab should show next (set by the Tours tab). */
    val tourToShow = MutableStateFlow<Long?>(null)

    /** Overview of all recorded tours the Map tab should show next (set by the Tours tab). */
    val toursOverview = MutableStateFlow<OverviewRequest?>(null)
}
