package de.wolaang.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import de.wolaang.app.data.model.PlannedRoute
import de.wolaang.app.data.model.Tour

@Database(
    entities = [Tour::class, PlannedRoute::class],
    version = 1,
    exportSchema = false
)
abstract class WolaangDatabase : RoomDatabase() {
    abstract fun tourDao(): TourDao
    abstract fun plannedRouteDao(): PlannedRouteDao
}
