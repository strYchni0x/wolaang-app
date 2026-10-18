package de.wolaang.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import de.wolaang.app.data.model.Tour
import kotlinx.coroutines.flow.Flow

/**
 * Queries returning [Tour] leave out the GPX (gpxData = ""): a long track can be bigger than
 * the 2 MB Android reads per row. The GPX is read in pieces, see [getTourById] / [gpxOf].
 */
@Dao
interface TourDao {
    @Query("SELECT $TOUR_COLUMNS FROM tours ORDER BY startedAt DESC")
    fun getAllTours(): Flow<List<Tour>>

    @Query("SELECT $TOUR_COLUMNS FROM tours WHERE id = :id")
    suspend fun getTourWithoutGpx(id: Long): Tour?

    @Query("SELECT length(gpxData) FROM tours WHERE id = :id")
    suspend fun gpxLength(id: Long): Int?

    /** [length] characters of the GPX from [start] (1-based, as in SQL substr). */
    @Query("SELECT substr(gpxData, :start, :length) FROM tours WHERE id = :id")
    suspend fun gpxPart(id: Long, start: Int, length: Int): String?

    /** Whether a tour starting within [toleranceMs] of [startedAt] with about this length exists. */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM tours WHERE ABS(startedAt - :startedAt) <= :toleranceMs " +
            "AND ABS(distanceMeters - :distanceMeters) <= :distanceTolerance)"
    )
    suspend fun hasTourAt(startedAt: Long, toleranceMs: Long, distanceMeters: Float, distanceTolerance: Float): Boolean

    /** For tracks without timestamps: same name and about the same length. */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM tours WHERE name = :name " +
            "AND ABS(distanceMeters - :distanceMeters) <= :distanceTolerance)"
    )
    suspend fun hasTourNamed(name: String, distanceMeters: Float, distanceTolerance: Float): Boolean

    @Insert
    suspend fun insertTour(tour: Tour): Long

    @Update
    suspend fun updateTour(tour: Tour)

    /** Ids of the tours whose countries have not been determined yet. */
    @Query("SELECT id FROM tours WHERE countries = ''")
    fun getUntaggedTourIds(): Flow<List<Long>>

    @Query("UPDATE tours SET countries = :countries WHERE id = :id")
    suspend fun setCountries(id: Long, countries: String)

    @Query("DELETE FROM tours WHERE id = :id")
    suspend fun deleteTour(id: Long)

    companion object {
        const val TOUR_COLUMNS = "id, name, type, startedAt, finishedAt, distanceMeters, durationSeconds, " +
            "elevationGainMeters, '' AS gpxData, notes, countries"
    }
}
