package de.wolaang.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import de.wolaang.app.data.model.PlannedRoute
import kotlinx.coroutines.flow.Flow

/** Like [TourDao]: queries returning [PlannedRoute] leave out the GPX, which is read in pieces. */
@Dao
interface PlannedRouteDao {
    @Query("SELECT $ROUTE_COLUMNS FROM planned_routes ORDER BY createdAt DESC")
    fun getAllPlannedRoutes(): Flow<List<PlannedRoute>>

    @Query("SELECT $ROUTE_COLUMNS FROM planned_routes WHERE id = :id")
    suspend fun getRouteWithoutGpx(id: Long): PlannedRoute?

    @Query("SELECT length(gpxData) FROM planned_routes WHERE id = :id")
    suspend fun gpxLength(id: Long): Int?

    /** [length] characters of the GPX from [start] (1-based, as in SQL substr). */
    @Query("SELECT substr(gpxData, :start, :length) FROM planned_routes WHERE id = :id")
    suspend fun gpxPart(id: Long, start: Int, length: Int): String?

    /** Whether a route with this name and about this length exists. */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM planned_routes WHERE name = :name " +
            "AND ABS(distanceMeters - :distanceMeters) <= :distanceTolerance)"
    )
    suspend fun hasRouteNamed(name: String, distanceMeters: Float, distanceTolerance: Float): Boolean

    @Insert
    suspend fun insertPlannedRoute(route: PlannedRoute): Long

    @Update
    suspend fun updatePlannedRoute(route: PlannedRoute)

    /** Ids of the routes whose countries have not been determined yet. */
    @Query("SELECT id FROM planned_routes WHERE countries = ''")
    fun getUntaggedRouteIds(): Flow<List<Long>>

    @Query("UPDATE planned_routes SET countries = :countries WHERE id = :id")
    suspend fun setCountries(id: Long, countries: String)

    @Query("DELETE FROM planned_routes WHERE id = :id")
    suspend fun deletePlannedRoute(id: Long)

    companion object {
        const val ROUTE_COLUMNS = "id, name, type, createdAt, waypointsJson, distanceMeters, ascentMeters, " +
            "estimatedDurationSeconds, '' AS gpxData, notes, countries"
    }
}
