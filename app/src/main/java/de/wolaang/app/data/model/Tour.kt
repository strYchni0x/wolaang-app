package de.wolaang.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

enum class TourType { HIKE, BIKE }

@Entity(tableName = "tours")
data class Tour(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: TourType,
    val startedAt: Long,
    val finishedAt: Long?,
    val distanceMeters: Float,
    val durationSeconds: Long,
    val elevationGainMeters: Float,
    val gpxData: String,   // GPX XML stored as text
    val notes: String = "",
    /** ISO codes of the countries passed ("DE,LU"), "-" if none found; "" = not determined yet. */
    @ColumnInfo(defaultValue = "") val countries: String = "",
)
