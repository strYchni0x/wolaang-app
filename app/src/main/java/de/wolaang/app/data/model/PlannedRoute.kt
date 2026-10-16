package de.wolaang.app.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "planned_routes")
data class PlannedRoute(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: TourType,
    val createdAt: Long,
    val waypointsJson: String,  // JSON array of lat/lon pairs
    val distanceMeters: Float,
    val estimatedDurationSeconds: Long,
    val notes: String = ""
)
