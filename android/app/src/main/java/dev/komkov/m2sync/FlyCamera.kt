package dev.komkov.m2sync

import androidx.compose.ui.geometry.Offset
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Точка в локальных метрах: восток, север, высота. */
data class Vec3(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)

    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)

    operator fun times(k: Double) = Vec3(x * k, y * k, z * k)

    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z

    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    fun normalized(): Vec3 {
        val len = sqrt(x * x + y * y + z * z)
        return if (len < 1e-9) this else Vec3(x / len, y / len, z / len)
    }
}

/**
 * Камера полёта: перспективная проекция мира в экран.
 *
 * Своя, а не движок: рисовать надо ровно четыре вещи — землю, ленту трека,
 * стену профиля и метку, — и ради них тянуть OpenGL-зависимость незачем.
 * Крена нет по построению (правый вектор всегда горизонтален), поэтому линия
 * горизонта на экране — это просто одна координата Y.
 */
class FlyCamera(
    val eye: Vec3,
    target: Vec3,
    private val widthPx: Float,
    private val heightPx: Float,
    fovDegrees: Double = 64.0,
) {
    companion object {
        /** Ближе этого не рисуем: за плоскостью проекция выворачивается. */
        const val NEAR = 2.0
    }

    val forward: Vec3 = (target - eye).normalized()
    val right: Vec3 = forward.cross(Vec3(0.0, 0.0, 1.0)).normalized()
    val up: Vec3 = right.cross(forward)

    private val focal: Double = (widthPx / 2.0) / tan(Math.toRadians(fovDegrees / 2))

    /** Экранный Y бесконечно далёкой земли. Ниже него — земля, выше — небо. */
    val horizonY: Float =
        run {
            val ahead = Vec3(forward.x, forward.y, 0.0).normalized()
            val vz = ahead.dot(forward)
            if (vz <= 1e-6) -heightPx else (heightPx / 2 - focal * ahead.dot(up) / vz).toFloat()
        }

    /** null, если точка за ближней плоскостью и рисовать её нельзя. */
    fun project(
        p: Vec3,
        near: Double = NEAR,
    ): Offset? {
        val d = p - eye
        val vz = d.dot(forward)
        if (vz < near) return null
        return Offset(
            (widthPx / 2 + focal * d.dot(right) / vz).toFloat(),
            (heightPx / 2 - focal * d.dot(up) / vz).toFloat(),
        )
    }

    /** Сколько экранных пикселей занимает [meters] метров на глубине точки [p]. */
    fun pixelsPerMeter(
        p: Vec3,
        meters: Double = 1.0,
    ): Float {
        val vz = (p - eye).dot(forward)
        if (vz < NEAR) return 0f
        return (focal * meters / vz).toFloat()
    }

    /**
     * Отрезок, обрезанный по ближней плоскости. Нужен линиям сетки: они уходят
     * за спину, и без обрезки половина сетки просто пропадала бы.
     */
    fun segment(
        a: Vec3,
        b: Vec3,
    ): Pair<Offset, Offset>? {
        val za = (a - eye).dot(forward)
        val zb = (b - eye).dot(forward)
        if (za < NEAR && zb < NEAR) return null
        var from = a
        var to = b
        if (za < NEAR) from = a + (b - a) * ((NEAR - za) / (zb - za))
        if (zb < NEAR) to = b + (a - b) * ((NEAR - zb) / (za - zb))
        // Обрезанный конец садится ровно на плоскость, и на округлении может
        // не пройти проверку — поэтому проецируем с микроскопическим запасом.
        val pa = project(from, NEAR - 1e-3) ?: return null
        val pb = project(to, NEAR - 1e-3) ?: return null
        return pa to pb
    }
}

/**
 * Точка маршрута на отметке [meters] метров от старта, с линейной интерполяцией
 * между записями. За пределами трека продолжает прямо по курсу конца — так
 * камере есть куда встать перед стартом и куда смотреть после финиша.
 */
fun RideTrack.poseAt(meters: Double): Vec3 {
    if (points.isEmpty()) return Vec3(0.0, 0.0, 0.0)
    val first = points.first()
    val last = points.last()
    if (meters <= first.distance) {
        val h = headingAt(0)
        val back = first.distance - meters
        return Vec3(first.x - cos(h) * back, first.y - sin(h) * back, first.altitude)
    }
    if (meters >= last.distance) {
        val h = headingAt(points.lastIndex)
        val ahead = meters - last.distance
        return Vec3(last.x + cos(h) * ahead, last.y + sin(h) * ahead, last.altitude)
    }
    val i = indexAtDistance(meters).coerceIn(1, points.lastIndex)
    val a = points[i - 1]
    val b = points[i]
    val span = b.distance - a.distance
    val t = if (span <= 1e-6) 0.0 else ((meters - a.distance) / span).coerceIn(0.0, 1.0)
    return Vec3(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.altitude + (b.altitude - a.altitude) * t)
}
