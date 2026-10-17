package de.wolaang.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import de.wolaang.app.data.model.PlannedRoute
import kotlinx.coroutines.flow.Flow

@Dao
interface PlannedRouteDao {
    @Query("SELECT * FROM planned_routes ORDER BY createdAt DESC")
    fun getAllPlannedRoutes(): Flow<List<PlannedRoute>>

    @Query("SELECT * FROM planned_routes WHERE id = :id")
    suspend fun getPlannedRouteById(id: Long): PlannedRoute?

    @Insert
    suspend fun insertPlannedRoute(route: PlannedRoute): Long

    @Update
    suspend fun updatePlannedRoute(route: PlannedRoute)

    /** Routes whose countries have not been determined yet. */
    @Query("SELECT * FROM planned_routes WHERE countries = ''")
    fun getUntaggedRoutes(): Flow<List<PlannedRoute>>

    @Query("UPDATE planned_routes SET countries = :countries WHERE id = :id")
    suspend fun setCountries(id: Long, countries: String)

    @Query("DELETE FROM planned_routes WHERE id = :id")
    suspend fun deletePlannedRoute(id: Long)
}
