package de.wolaang.app.data.db

import de.wolaang.app.data.model.PlannedRoute
import de.wolaang.app.data.model.Tour

// Characters per read; at most 3 bytes each in UTF-8, so well below the 2 MB row limit.
private const val GPX_PART_CHARS = 400_000

private suspend fun readInParts(length: Int?, part: suspend (start: Int, length: Int) -> String?): String {
    if (length == null || length == 0) return ""
    val sb = StringBuilder(length)
    var start = 1
    while (start <= length) {
        sb.append(part(start, GPX_PART_CHARS) ?: break)
        start += GPX_PART_CHARS
    }
    return sb.toString()
}

suspend fun TourDao.gpxOf(id: Long): String = readInParts(gpxLength(id)) { s, l -> gpxPart(id, s, l) }

/** The tour including its GPX. */
suspend fun TourDao.getTourById(id: Long): Tour? = getTourWithoutGpx(id)?.copy(gpxData = gpxOf(id))

suspend fun PlannedRouteDao.gpxOf(id: Long): String = readInParts(gpxLength(id)) { s, l -> gpxPart(id, s, l) }

/** The route including its GPX. */
suspend fun PlannedRouteDao.getPlannedRouteById(id: Long): PlannedRoute? =
    getRouteWithoutGpx(id)?.copy(gpxData = gpxOf(id))
