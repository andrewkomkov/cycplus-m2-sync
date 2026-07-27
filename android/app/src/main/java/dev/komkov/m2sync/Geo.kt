package dev.komkov.m2sync

import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sinh
import kotlin.math.tan

/**
 * Веб-меркатор и локальные метры.
 *
 * Одна и та же сетка обслуживает и плоскую карту, и полёт: карта кладёт на неё
 * тайлы OpenStreetMap, полёт — ту же землю, только в перспективе. Поэтому
 * математика лежит отдельно от обоих экранов.
 */
object Geo {
    /** Сторона растрового тайла OSM в пикселях. */
    const val TILE_PX = 256

    /** Дробная координата тайла по долготе на зуме [z]. */
    fun tileX(
        lon: Double,
        z: Int,
    ): Double = (lon + 180.0) / 360.0 * (1 shl z)

    /**
     * Дробная координата тайла по широте. Полюса в меркаторе уходят в
     * бесконечность, поэтому широту режем по стандартной границе ±85.0511°.
     */
    fun tileY(
        lat: Double,
        z: Int,
    ): Double {
        val r = Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878))
        return (1.0 - asinh(tan(r)) / PI) / 2.0 * (1 shl z)
    }

    fun lonOfTileX(
        x: Double,
        z: Int,
    ): Double = x / (1 shl z) * 360.0 - 180.0

    fun latOfTileY(
        y: Double,
        z: Int,
    ): Double = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y / (1 shl z)))))

    private const val METERS_PER_DEG_LAT = 111_320.0

    fun metersPerDegLon(lat: Double): Double = METERS_PER_DEG_LAT * cos(Math.toRadians(lat))

    /**
     * Плоские метры относительно опорной точки. На масштабе одного заезда
     * (десятки километров) равнопромежуточной проекции достаточно: искажение
     * меньше, чем точность самого GPS велокомпьютера.
     */
    fun toLocalX(
        lon: Double,
        originLon: Double,
        originLat: Double,
    ): Double = (lon - originLon) * metersPerDegLon(originLat)

    fun toLocalY(
        lat: Double,
        originLat: Double,
    ): Double = (lat - originLat) * METERS_PER_DEG_LAT
}

/** Охватывающий прямоугольник маршрута. */
data class GeoBounds(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
) {
    val centerLat: Double get() = (minLat + maxLat) / 2
    val centerLon: Double get() = (minLon + maxLon) / 2

    /**
     * Максимальный зум, на котором маршрут целиком влезает в [widthPx]×[heightPx].
     * [padding] — доля кадра, оставленная по краям, чтобы трек не лип к рамке.
     */
    fun fitZoom(
        widthPx: Float,
        heightPx: Float,
        padding: Float = 0.12f,
    ): Int {
        if (widthPx <= 0 || heightPx <= 0) return MIN_ZOOM
        val usableW = widthPx * (1 - padding * 2)
        val usableH = heightPx * (1 - padding * 2)
        for (z in MAX_ZOOM downTo MIN_ZOOM) {
            val w = (Geo.tileX(maxLon, z) - Geo.tileX(minLon, z)) * Geo.TILE_PX
            // По широте оси перевёрнуты: y растёт вниз, поэтому вычитаем наоборот.
            val h = (Geo.tileY(minLat, z) - Geo.tileY(maxLat, z)) * Geo.TILE_PX
            if (w <= usableW && h <= usableH) return z
        }
        return MIN_ZOOM
    }

    companion object {
        const val MIN_ZOOM = 2
        const val MAX_ZOOM = 18

        /** null, если ни в одной точке нет координат. */
        fun of(points: List<TrackPoint>): GeoBounds? {
            if (points.isEmpty()) return null
            var minLat = Double.MAX_VALUE
            var maxLat = -Double.MAX_VALUE
            var minLon = Double.MAX_VALUE
            var maxLon = -Double.MAX_VALUE
            for (p in points) {
                if (p.lat < minLat) minLat = p.lat
                if (p.lat > maxLat) maxLat = p.lat
                if (p.lon < minLon) minLon = p.lon
                if (p.lon > maxLon) maxLon = p.lon
            }
            // Стоянка на месте вырождает прямоугольник в точку, и подбор зума
            // уезжает в максимум — поэтому держим минимальный размер около 10 м.
            val minSpan = 1e-4
            val cLat = (minLat + maxLat) / 2
            val cLon = (minLon + maxLon) / 2
            val halfLat = maxOf((maxLat - minLat) / 2, minSpan / 2)
            val halfLon = maxOf((maxLon - minLon) / 2, minSpan / 2)
            return GeoBounds(cLat - halfLat, cLat + halfLat, cLon - halfLon, cLon + halfLon)
        }
    }
}
