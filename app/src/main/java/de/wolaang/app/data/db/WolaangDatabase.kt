package de.wolaang.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import de.wolaang.app.data.model.PlannedRoute
import de.wolaang.app.data.model.Tour

@Database(
    entities = [Tour::class, PlannedRoute::class],
    version = 5,
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

        /** v3: countries column on both tables, filled in afterwards by CountryTagger. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `tours` ADD COLUMN `countries` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `planned_routes` ADD COLUMN `countries` TEXT NOT NULL DEFAULT ''")
            }
        }

        /** Clears the stored countries so all entries are tagged again (after border corrections). */
        private fun retagCountries(from: Int, to: Int) = object : Migration(from, to) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE `tours` SET `countries` = ''")
                db.execSQL("UPDATE `planned_routes` SET `countries` = ''")
            }
        }

        /** v4: small islands fall back to the nearest country. */
        val MIGRATION_3_4 = retagCountries(3, 4)

        /** v5: Natural Earth 1:10m borders, Konstanz corrected. */
        val MIGRATION_4_5 = retagCountries(4, 5)
    }
}
