package de.wolaang.app.data

import android.content.Context
import de.wolaang.app.data.model.TourType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Online = OSM tiles from openstreetmap.org, offline = downloaded Mapsforge maps. */
enum class MapSource { ONLINE, OFFLINE }

/** User preferences, persisted in SharedPreferences and exposed as flows for Compose. */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(
        prefs.getString(KEY_THEME_MODE, null)
            ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.SYSTEM
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
        _themeMode.value = mode
    }

    private val _importTarget = MutableStateFlow(
        prefs.getString(KEY_IMPORT_TARGET, null)
            ?.let { runCatching { ImportTarget.valueOf(it) }.getOrNull() }
            ?: ImportTarget.PLANNED
    )

    /** Default target for GPX imports (planned routes; recorded tours e.g. for a Komoot move). */
    val importTarget: StateFlow<ImportTarget> = _importTarget.asStateFlow()

    fun setImportTarget(target: ImportTarget) {
        prefs.edit().putString(KEY_IMPORT_TARGET, target.name).apply()
        _importTarget.value = target
    }

    private val _mapSource = MutableStateFlow(
        prefs.getString(KEY_MAP_SOURCE, null)
            ?.let { runCatching { MapSource.valueOf(it) }.getOrNull() }
            ?: MapSource.ONLINE
    )
    val mapSource: StateFlow<MapSource> = _mapSource.asStateFlow()

    fun setMapSource(source: MapSource) {
        prefs.edit().putString(KEY_MAP_SOURCE, source.name).apply()
        _mapSource.value = source
    }

    /** Activity type chosen for the last recording; preselected next time. */
    var lastRecordingType: TourType
        get() = prefs.getString(KEY_LAST_RECORDING_TYPE, null)
            ?.let { runCatching { TourType.valueOf(it) }.getOrNull() }
            ?: TourType.HIKE
        set(value) = prefs.edit().putString(KEY_LAST_RECORDING_TYPE, value.name).apply()

    private companion object {
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_LAST_RECORDING_TYPE = "last_recording_type"
        const val KEY_IMPORT_TARGET = "import_target"
        const val KEY_MAP_SOURCE = "map_source"
    }
}
