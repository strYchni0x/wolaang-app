package de.wolaang.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import de.wolaang.app.data.model.PlannedRoute
import de.wolaang.app.data.model.Tour

@Database(
    entities = [Tour::class, PlannedRoute::class],
    version = 2,
    exportSchema = false
)
abstract class WolaangDatabase : RoomDatabase() {
    abstract fun tourDao(): TourDao
    abstract fun plannedRouteDao(): PlannedRouteDao

    companion object {
        /**
         * v2: planned_routes gets ascentMeters + gpxData. In v1 there was no UI to save
         * planned routes, so the table is always empty and can simply be recreated.
         * Recorded tours are untouched.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `planned_routes`")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `planned_routes` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, " +
                        "`type` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `waypointsJson` TEXT NOT NULL, " +
                        "`distanceMeters` REAL NOT NULL, `ascentMeters` INTEGER NOT NULL, " +
                        "`estimatedDurationSeconds` INTEGER NOT NULL, `gpxData` TEXT NOT NULL, " +
                        "`notes` TEXT NOT NULL)"
                )
            }
        }
    }
}
