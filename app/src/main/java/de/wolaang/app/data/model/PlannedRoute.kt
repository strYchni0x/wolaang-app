package de.wolaang.app.data.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "planned_routes")
data class PlannedRoute(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: TourType,
    val createdAt: Long,
    val waypointsJson: String,  // JSON array of [lat, lon] pairs (the tapped points)
    val distanceMeters: Float,
    val ascentMeters: Int,
    val estimatedDurationSeconds: Long,
    val gpxData: String,        // computed route geometry as GPX XML
    val notes: String = "",
    /** ISO codes of the countries passed ("DE,LU"), "-" if none found; "" = not determined yet. */
    @ColumnInfo(defaultValue = "") val countries: String = "",
)
