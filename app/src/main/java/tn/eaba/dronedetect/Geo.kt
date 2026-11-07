package tn.eaba.dronedetect

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Calculs geodesiques sur sphere. Suffisant aux distances tactiques. */
object Geo {

    private const val R = 6371000.0

    /**
     * Point destination : depuis (lat, lon), au relevement [azimut] en degres,
     * a [distance] metres. Formule du point direct.
     */
    fun destination(
        lat: Double, lon: Double, azimut: Double, distance: Double
    ): Pair<Double, Double> {
        val d = distance / R
        val t = Math.toRadians(azimut)
        val p1 = Math.toRadians(lat)
        val l1 = Math.toRadians(lon)
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(t))
        val l2 = l1 + atan2(sin(t) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return Pair(Math.toDegrees(p2), (Math.toDegrees(l2) + 540.0) % 360.0 - 180.0)
    }

    /** Points d'un cercle de rayon [rayon] metres autour de (lat, lon). */
    fun cercle(lat: Double, lon: Double, rayon: Double, pas: Int = 72):
            List<Pair<Double, Double>> =
        (0 until pas).map { destination(lat, lon, it * 360.0 / pas, rayon) }
}