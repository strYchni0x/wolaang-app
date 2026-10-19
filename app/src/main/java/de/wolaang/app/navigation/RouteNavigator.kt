package de.wolaang.app.navigation

import android.location.Location
import de.wolaang.app.data.model.TourType
import de.wolaang.app.routing.RoutePoint
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/** Direction of a turn, from the change of heading at a point of the route. */
enum class TurnKind(val label: String) {
    SLIGHT_LEFT("leicht links"),
    LEFT("links abbiegen"),
    SHARP_LEFT("scharf links"),
    SLIGHT_RIGHT("leicht rechts"),
    RIGHT("rechts abbiegen"),
    SHARP_RIGHT("scharf rechts"),
    ARRIVE("Ziel"),
}

/** A turn at [alongMeters] from the start; [angle] is the heading change, negative = left. */
class Maneuver(val alongMeters: Double, val kind: TurnKind, val angle: Float)

/** A route to navigate along (a planned or saved route from the Plan tab). */
class NavigationRoute(val name: String, val type: TourType, val points: List<RoutePoint>) {
    /** Distance from the start to each point. */
    val along: DoubleArray = DoubleArray(points.size).also { a ->
        val d = FloatArray(1)
        for (i in 1 until points.size) {
            Location.distanceBetween(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon, d)
            a[i] = a[i - 1] + d[0]
        }
    }
    val lengthMeters: Double get() = along.lastOrNull() ?: 0.0
    val maneuvers: List<Maneuver> = findManeuvers()

    /** Point at [s] meters along the route. */
    fun pointAt(s: Double): RoutePoint {
        val i = segmentAt(s)
        if (i >= points.lastIndex) return points.last()
        val len = along[i + 1] - along[i]
        val t = if (len > 0) ((s - along[i]) / len).coerceIn(0.0, 1.0) else 0.0
        val a = points[i]; val b = points[i + 1]
        return RoutePoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t, null)
    }

    private fun segmentAt(s: Double): Int {
        var lo = 0; var hi = points.lastIndex
        while (lo < hi - 1) {
            val mid = (lo + hi) / 2
            if (along[mid] <= s) lo = mid else hi = mid
        }
        return lo
    }

    private fun bearing(from: RoutePoint, to: RoutePoint): Float {
        val r = FloatArray(2)
        Location.distanceBetween(from.lat, from.lon, to.lat, to.lon, r)
        return r[1]
    }

    /**
     * Turns from the geometry alone, so imported routes work too: at every point, compare the
     * heading over the TURN_WINDOW_M before and after it; a change of at least MIN_TURN_DEG is
     * a turn. Of turns closer together than MERGE_M, the sharpest one is kept.
     */
    private fun findManeuvers(): List<Maneuver> {
        if (points.size < 3) return listOf(Maneuver(lengthMeters, TurnKind.ARRIVE, 0f))
        val candidates = ArrayList<Maneuver>()
        for (i in 1 until points.lastIndex) {
            val s = along[i]
            if (s < TURN_WINDOW_M || s > lengthMeters - TURN_WINDOW_M) continue
            val before = pointAt(s - TURN_WINDOW_M)
            val after = pointAt(s + TURN_WINDOW_M)
            val angle = normalize(bearing(points[i], after) - bearing(before, points[i]))
            if (abs(angle) >= MIN_TURN_DEG) candidates.add(Maneuver(s, kindOf(angle), angle))
        }
        val merged = ArrayList<Maneuver>()
        for (m in candidates) {
            val last = merged.lastOrNull()
            if (last != null && m.alongMeters - last.alongMeters < MERGE_M) {
                if (abs(m.angle) > abs(last.angle)) merged[merged.lastIndex] = m
            } else {
                merged.add(m)
            }
        }
        merged.add(Maneuver(lengthMeters, TurnKind.ARRIVE, 0f))
        return merged
    }

    private fun kindOf(angle: Float): TurnKind {
        val a = abs(angle)
        val left = angle < 0
        return when {
            a < 60 -> if (left) TurnKind.SLIGHT_LEFT else TurnKind.SLIGHT_RIGHT
            a < 135 -> if (left) TurnKind.LEFT else TurnKind.RIGHT
            else -> if (left) TurnKind.SHARP_LEFT else TurnKind.SHARP_RIGHT
        }
    }

    private companion object {
        const val TURN_WINDOW_M = 25.0
        const val MIN_TURN_DEG = 35f
        const val MERGE_M = 30.0

        fun normalize(deg: Float): Float {
            var d = deg % 360f
            if (d > 180f) d -= 360f
            if (d < -180f) d += 360f
            return d
        }
    }
}

/** Where the user is relative to the route. */
class NavigationProgress(
    /** Meters along the route of the closest point. */
    val alongMeters: Double,
    val remainingMeters: Double,
    /** Distance from the route; large = off route. */
    val offRouteMeters: Double,
    /** Closest point on the route (to lead back when off route). */
    val closest: RoutePoint,
    val next: Maneuver,
    val toNextMeters: Double,
    /** GPS accuracy of the fix; a worse fix needs a larger distance to count as off route. */
    private val accuracyMeters: Double,
) {
    val isOffRoute: Boolean get() = offRouteMeters > maxOf(OFF_ROUTE_M, accuracyMeters)
    val hasArrived: Boolean get() = remainingMeters < ARRIVED_M && !isOffRoute

    companion object {
        const val OFF_ROUTE_M = 40.0
        const val ARRIVED_M = 30.0
    }
}

/**
 * Follows a position along a [NavigationRoute]. Matches each fix to the nearest part of the
 * route, preferring the stretch just ahead of the last match, so loops and out-and-back
 * routes are followed in order.
 */
class RouteNavigator(val route: NavigationRoute) {
    private var lastAlong = 0.0

    fun update(lat: Double, lon: Double, accuracyMeters: Double): NavigationProgress {
        val ahead = nearest(lat, lon, lastAlong - BACK_WINDOW_M, lastAlong + AHEAD_WINDOW_M)
        val match = if (ahead.second <= NavigationProgress.OFF_ROUTE_M) ahead
        else nearest(lat, lon, 0.0, route.lengthMeters).takeIf { it.second < ahead.second } ?: ahead
        lastAlong = match.first
        val next = route.maneuvers.first { it.alongMeters > lastAlong + PASSED_M }
        return NavigationProgress(
            alongMeters = lastAlong,
            remainingMeters = route.lengthMeters - lastAlong,
            offRouteMeters = match.second,
            closest = route.pointAt(lastAlong),
            next = next,
            toNextMeters = next.alongMeters - lastAlong,
            accuracyMeters = accuracyMeters,
        )
    }

    /** (meters along, distance) of the closest route point between [from] and [to] meters. */
    private fun nearest(lat: Double, lon: Double, from: Double, to: Double): Pair<Double, Double> {
        val pts = route.points
        val k = cos(Math.toRadians(lat))
        var best = Double.MAX_VALUE
        var bestAlong = 0.0
        for (i in 0 until pts.lastIndex) {
            if (route.along[i + 1] < from || route.along[i] > to) continue
            // Local flat projection in meters around the position.
            val ax = (pts[i].lon - lon) * k * M_PER_DEG; val ay = (pts[i].lat - lat) * M_PER_DEG
            val bx = (pts[i + 1].lon - lon) * k * M_PER_DEG; val by = (pts[i + 1].lat - lat) * M_PER_DEG
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 > 0) (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0) else 0.0
            val d = hypot(ax + t * dx, ay + t * dy)
            if (d < best) {
                best = d
                bestAlong = route.along[i] + t * (route.along[i + 1] - route.along[i])
            }
        }
        return bestAlong to best
    }

    private companion object {
        const val M_PER_DEG = 111_320.0
        const val BACK_WINDOW_M = 100.0
        const val AHEAD_WINDOW_M = 1500.0
        // A turn counts as passed this far behind it, so it stays shown while turning.
        const val PASSED_M = 10.0
    }
}
