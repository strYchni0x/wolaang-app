package de.wolaang.app.data

/** Height changes smaller than this are not counted as climbing or descending (GPS noise). */
const val ELEVATION_HYSTERESIS_M = 3.0

/** Total climb of an elevation series, counting a climb once it reaches the hysteresis. */
fun ascentOf(elevations: List<Double>): Double {
    var ascent = 0.0
    var reference = elevations.firstOrNull() ?: return 0.0
    for (e in elevations) {
        when {
            e - reference >= ELEVATION_HYSTERESIS_M -> { ascent += e - reference; reference = e }
            e < reference -> reference = e
        }
    }
    return ascent
}

/** Total descent, the mirror image of [ascentOf]. */
fun descentOf(elevations: List<Double>): Double = ascentOf(elevations.map { -it })
