package de.wolaang.app.data

import android.content.Context
import androidx.room.Room
import de.wolaang.app.data.db.PlannedRouteDao
import de.wolaang.app.data.db.TourDao
import de.wolaang.app.data.db.WolaangDatabase
import de.wolaang.app.tracking.TrackRecorder

/**
 * Simple manual dependency container.
 * Created once in [de.wolaang.app.WolaangApp] and accessed via the Application instance.
 */
class AppContainer(context: Context) {

    private val database: WolaangDatabase = Room.databaseBuilder(
        context.applicationContext,
        WolaangDatabase::class.java,
        "wolaang.db"
    ).build()

    val tourDao: TourDao = database.tourDao()
    val plannedRouteDao: PlannedRouteDao = database.plannedRouteDao()

    /** Shared live state of the current recording (written by TrackingService). */
    val trackRecorder: TrackRecorder = TrackRecorder()
}
