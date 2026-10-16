package de.wolaang.app.tracking

import android.location.Location
import de.wolaang.app.data.model.TourType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val altitude: Double,
    val time: Long,
)

data class RecordingState(
    val isRecording: Boolean = false,
    val points: List<TrackPoint> = emptyList(),
    val distanceMeters: Float = 0f,
    val elevationGainMeters: Float = 0f,
    val startedAt: Long = 0L,
    val type: TourType = TourType.HIKE,
)

/**
 * Holds the live state of an ongoing recording. Written to by [TrackingService]
 * (one instance per app, provided via the AppContainer) and observed by the UI.
 */
class TrackRecorder {

    private val _state = MutableStateFlow(RecordingState())
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    fun start(type: TourType) {
        _state.value = RecordingState(isRecording = true, startedAt = System.currentTimeMillis(), type = type)
    }

    fun addPoint(location: Location) {
        val current = _state.value
        if (!current.isRecording) return

        val point = TrackPoint(location.latitude, location.longitude, location.altitude, location.time)
        var distance = current.distanceMeters
        var elevation = current.elevationGainMeters

        current.points.lastOrNull()?.let { last ->
            val result = FloatArray(1)
            Location.distanceBetween(last.lat, last.lon, point.lat, point.lon, result)
            distance += result[0]
            val dAlt = point.altitude - last.altitude
            if (dAlt > 0) elevation += dAlt.toFloat()
        }

        _state.value = current.copy(
            points = current.points + point,
            distanceMeters = distance,
            elevationGainMeters = elevation,
        )
    }

    /** Stops recording, returns the final state, and resets back to idle. */
    fun stop(): RecordingState {
        val finished = _state.value.copy(isRecording = false)
        _state.value = RecordingState()
        return finished
    }
}
