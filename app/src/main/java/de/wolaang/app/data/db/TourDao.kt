package de.wolaang.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import de.wolaang.app.data.model.Tour
import kotlinx.coroutines.flow.Flow

@Dao
interface TourDao {
    @Query("SELECT * FROM tours ORDER BY startedAt DESC")
    fun getAllTours(): Flow<List<Tour>>

    @Query("SELECT * FROM tours WHERE id = :id")
    suspend fun getTourById(id: Long): Tour?

    @Insert
    suspend fun insertTour(tour: Tour): Long

    @Update
    suspend fun updateTour(tour: Tour)

    /** Tours whose countries have not been determined yet. */
    @Query("SELECT * FROM tours WHERE countries = ''")
    fun getUntaggedTours(): Flow<List<Tour>>

    @Query("UPDATE tours SET countries = :countries WHERE id = :id")
    suspend fun setCountries(id: Long, countries: String)

    @Query("DELETE FROM tours WHERE id = :id")
    suspend fun deleteTour(id: Long)
}
