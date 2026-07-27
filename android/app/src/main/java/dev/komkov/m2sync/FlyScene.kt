package dev.komkov.m2sync

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Цвета сцены полёта — вытаскиваются из темы один раз и передаются в отрисовку. */
data class FlyPalette(
    val skyTop: Color,
    val skyHorizon: Color,
    val ground: Color,
    val grid: Color,
    val slow: Color,
    val mid: Color,
    val fast: Color,
    val marker: Color,
    val milestone: Color,
    /** Плотность дымки у горизонта и доля кадра, которую она занимает. */
    val fogAlpha: Float = 0.7f,
    val fogSpan: Float = 0.30f,
) {
    /** Цвет ленты по скорости: медленно — один конец палитры, быстро — другой. */
    fun speedColor(
        kmh: Double,
        maxKmh: Double,
    ): Color {
        val t = if (maxKmh <= 1.0) 0.5f else (kmh / maxKmh).coerceIn(0.0, 1.0).toFloat()
        return if (t < 0.5f) lerp(slow, mid, t * 2) else lerp(mid, fast, (t - 0.5f) * 2)
    }
}

/** Насколько далеко видно вперёд, в метрах. Дальше уходит в дымку. */
private const val FAR = 1400.0

/** Полметра ленты в каждую сторону от осевой — так трек читается как дорога. */
private const val RIBBON_HALF_WIDTH = 6.0

/**
 * Зум земли: тайл выходит около 400 м. С птичьего полёта мельче не нужно —
 * подписи всё равно не прочитать, а квадратов на кадр набегало бы вчетверо
 * больше, и каждый — отдельный запрос к OpenStreetMap.
 */
const val FLY_GROUND_ZOOM = 16

/** Разбиение тайла при натягивании: 4×4 хватает, чтобы перспектива не ломалась. */
private const val MESH = 4

/** Больше тайлов за кадр не рисуем: и незачем, и сеть жалко. */
private const val MAX_TILES_PER_FRAME = 48

/**
 * Настройки погони: камера позади и выше райдера, смотрит вперёд по треку.
 * Высота взята с запасом — с птичьего полёта заезд читается как маршрут по
 * городу, а не как коридор из двух домов по сторонам.
 */
private const val CAMERA_BACK = 155.0
private const val CAMERA_HEIGHT = 95.0
private const val CAMERA_LOOK_AHEAD = 230.0

/** Пределы ручной камеры: дальше — трек теряется, ближе — упираемся в ленту. */
const val FLY_DISTANCE_MIN = 0.35f
const val FLY_DISTANCE_MAX = 4.0f
const val FLY_HEIGHT_MIN = 0.12f
const val FLY_HEIGHT_MAX = 3.5f

/**
 * Камера погони с ручными поправками.
 *
 * @param yaw облёт вокруг райдера, радианы; 0 — строго позади по треку
 * @param heightScale во сколько раз поднять камеру над треком
 * @param distanceScale во сколько раз отодвинуть камеру
 */
fun flyCamera(
    track: RideTrack,
    meters: Double,
    width: Float,
    height: Float,
    yaw: Float = 0f,
    heightScale: Float = 1f,
    distanceScale: Float = 1f,
): FlyCamera {
    val rider = track.poseAt(meters)
    val behind = track.poseAt(meters - CAMERA_BACK)
    val ahead = track.poseAt(meters + CAMERA_LOOK_AHEAD)

    val heading = kotlin.math.atan2(ahead.y - behind.y, ahead.x - behind.x)
    val angle = heading + Math.PI + yaw
    val distance = CAMERA_BACK * distanceScale

    // Отвернувшись в сторону, смотреть на сто метров вперёд по треку незачем —
    // там уже не то, что показывает камера. Поэтому взгляд плавно съезжает с
    // дороги впереди на самого райдера.
    val blend = kotlin.math.cos(yaw.toDouble()).coerceAtLeast(0.0)
    val look = track.poseAt(meters + CAMERA_LOOK_AHEAD * blend)

    return FlyCamera(
        eye =
            Vec3(
                rider.x + kotlin.math.cos(angle) * distance,
                rider.y + kotlin.math.sin(angle) * distance,
                rider.z + CAMERA_HEIGHT * heightScale * distanceScale,
            ),
        target = Vec3(look.x, look.y, look.z + 4.0),
        widthPx = width,
        heightPx = height,
    )
}

/** Фильтр для тайлов земли: приглушаем, чтобы лента трека оставалась главной. */
fun groundPaint(
    dark: Boolean,
    layer: MapLayer,
) = Paint().apply {
    isAntiAlias = true
    isFilterBitmap = true
    isDither = true
    colorFilter =
        ColorMatrixColorFilter(
            ColorMatrix().apply {
                // Приглушаем умеренно: сильнее — и карта под лентой превращается
                // в однотонное пятно, на котором не видно ни улиц, ни реки. Снимку
                // приглушение почти не нужно — он и так не спорит с палитрой.
                val satellite = layer == MapLayer.SATELLITE
                setSaturation(
                    if (satellite) {
                        0.95f
                    } else if (dark) {
                        0.55f
                    } else {
                        0.78f
                    },
                )
                val k = if (dark) (if (satellite) 0.75f else 0.55f) else (if (satellite) 1f else 0.97f)
                postConcat(
                    ColorMatrix(
                        floatArrayOf(
                            k,
                            0f,
                            0f,
                            0f,
                            0f,
                            0f,
                            k,
                            0f,
                            0f,
                            0f,
                            0f,
                            0f,
                            k,
                            0f,
                            0f,
                            0f,
                            0f,
                            0f,
                            1f,
                            0f,
                        ),
                    ),
                )
            },
        )
}

/**
 * Кадр полёта целиком: небо, земля, лента трека со стеной профиля и метка райдера.
 *
 * Порядок рисования и есть глубина: сначала дальнее, потом ближнее. Z-буфера
 * здесь нет и не нужно — сцена плоская по слоям, а трек рисуется от дальнего
 * конца к ближнему.
 */
fun DrawScope.drawFlyScene(
    track: RideTrack,
    camera: FlyCamera,
    meters: Double,
    tiles: TileSource,
    palette: FlyPalette,
    paint: Paint,
    layer: MapLayer,
) {
    val horizon = camera.horizonY.coerceIn(-size.height, size.height * 2)
    val groundZ = track.minAltitude - 2.0

    drawRect(
        brush =
            Brush.verticalGradient(
                0f to palette.skyTop,
                1f to palette.skyHorizon,
                startY = 0f,
                endY = max(horizon, 1f),
            ),
        size = Size(size.width, max(horizon, 0f)),
    )
    if (horizon < size.height) {
        drawRect(
            color = palette.ground,
            topLeft = Offset(0f, max(horizon, 0f)),
            size = Size(size.width, size.height - max(horizon, 0f)),
        )
    }

    val drawn = if (layer.enabled) drapeTiles(track, camera, tiles, groundZ, horizon, paint, layer) else 0
    if (drawn == 0) drawGroundGrid(camera, groundZ, palette)

    // Дымка у горизонта: прячет край натянутых тайлов и границу сетки, а заодно
    // даёт глубину — без неё дальний план выглядит наклеенным. Полоса узкая и
    // полупрозрачная: шире — и вымывает всю землю до однотонного пятна.
    if (horizon < size.height) {
        val fogTo = min(size.height, horizon + size.height * palette.fogSpan)
        drawRect(
            brush =
                Brush.verticalGradient(
                    0f to palette.skyHorizon.copy(alpha = palette.fogAlpha),
                    1f to palette.skyHorizon.copy(alpha = 0f),
                    startY = horizon,
                    endY = fogTo,
                ),
            topLeft = Offset(0f, max(horizon, 0f)),
            size = Size(size.width, fogTo - max(horizon, 0f)),
        )
    }

    drawMilestones(track, camera, meters, groundZ, palette)
    drawRibbon(track, camera, meters, groundZ, palette)
    drawRider(track, camera, meters, groundZ, palette)
}

/** @return сколько тайлов удалось положить на землю. */
private fun DrawScope.drapeTiles(
    track: RideTrack,
    camera: FlyCamera,
    tiles: TileSource,
    groundZ: Double,
    horizon: Float,
    paint: Paint,
    layer: MapLayer,
): Int {
    val origin = track.points.firstOrNull() ?: return 0
    // Подписка на догружающиеся тайлы: чтение перерисует кадр.
    if (tiles.revision < 0) return 0

    val ahead = Vec3(camera.forward.x, camera.forward.y, 0.0).normalized()
    val center = Vec3(camera.eye.x + ahead.x * FAR * 0.35, camera.eye.y + ahead.y * FAR * 0.35, 0.0)
    val half = FAR * 0.62

    val metersPerLon = Geo.metersPerDegLon(origin.lat)

    fun lonOf(x: Double) = origin.lon + x / metersPerLon

    fun latOf(y: Double) = origin.lat + y / 111_320.0

    val minLon = lonOf(center.x - half)
    val maxLon = lonOf(center.x + half)
    val minLat = latOf(center.y - half)
    val maxLat = latOf(center.y + half)

    val side = MESH + 1
    val verts = FloatArray(side * side * 2)
    val visible = BooleanArray(side * side)
    // Инструменты медленного пути — по одному набору на кадр, не на ячейку.
    val matrix = android.graphics.Matrix()
    val clip = android.graphics.Path()
    val src = FloatArray(8)
    val dst = FloatArray(8)
    var count = 0

    forEachTile(
        z = FLY_GROUND_ZOOM,
        minTileX = Geo.tileX(minLon, FLY_GROUND_ZOOM),
        maxTileX = Geo.tileX(maxLon, FLY_GROUND_ZOOM),
        minTileY = Geo.tileY(maxLat, FLY_GROUND_ZOOM),
        maxTileY = Geo.tileY(minLat, FLY_GROUND_ZOOM),
    ) { tx, ty ->
        if (count >= MAX_TILES_PER_FRAME) return@forEachTile
        val tile =
            tiles.tile(layer, FLY_GROUND_ZOOM, wrapTileX(tx, FLY_GROUND_ZOOM), ty)
                ?: return@forEachTile

        var whole = true
        var any = false
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (j in 0..MESH) {
            for (i in 0..MESH) {
                val lon = Geo.lonOfTileX(tx + i.toDouble() / MESH, FLY_GROUND_ZOOM)
                val lat = Geo.latOfTileY(ty + j.toDouble() / MESH, FLY_GROUND_ZOOM)
                val world =
                    Vec3(
                        Geo.toLocalX(lon, origin.lon, origin.lat),
                        Geo.toLocalY(lat, origin.lat),
                        groundZ,
                    )
                // Порог чуть больше ближней плоскости: вплотную к камере земля
                // растянулась бы на пол-экрана и рисовалась бы медленно и криво.
                val at = camera.project(world, near = 7.0)
                val node = j * side + i
                visible[node] = at != null
                if (at == null) {
                    whole = false
                } else {
                    any = true
                    verts[node * 2] = at.x
                    verts[node * 2 + 1] = at.y
                    minX = min(minX, at.x)
                    maxX = max(maxX, at.x)
                    minY = min(minY, at.y)
                    maxY = max(maxY, at.y)
                }
            }
        }
        if (!any) return@forEachTile
        // За экраном и выше горизонта рисовать нечего.
        if (maxX < 0 || minX > size.width || maxY < horizon - 4f || minY > size.height) return@forEachTile

        if (whole) {
            drawIntoCanvas {
                it.nativeCanvas.drawBitmapMesh(tile.bitmap, MESH, MESH, verts, 0, null, 0, paint)
            }
        } else {
            // Тайл задевает камеру. Сетку `drawBitmapMesh` обрезать нельзя, и
            // раньше такой тайл выбрасывался целиком — под камерой зияла дыра в
            // полкилометра. Поэтому раскладываем его по ячейкам: каждая ложится
            // своей перспективной матрицей, а выпадают только те, что и правда
            // за спиной.
            drawCells(tile, verts, visible, matrix, clip, src, dst, paint)
        }
        count++
    }
    return count
}

/** Поячеечная укладка тайла: медленный путь для плитки у самой камеры. */
private fun DrawScope.drawCells(
    tile: MapTile,
    verts: FloatArray,
    visible: BooleanArray,
    matrix: android.graphics.Matrix,
    clip: android.graphics.Path,
    src: FloatArray,
    dst: FloatArray,
    paint: Paint,
) {
    val side = MESH + 1
    val step = tile.bitmap.width.toFloat() / MESH
    val stepV = tile.bitmap.height.toFloat() / MESH

    for (j in 0 until MESH) {
        for (i in 0 until MESH) {
            val nodes =
                intArrayOf(
                    j * side + i,
                    j * side + i + 1,
                    (j + 1) * side + i + 1,
                    (j + 1) * side + i,
                )
            if (nodes.any { !visible[it] }) continue

            var cx = 0f
            var cy = 0f
            for (k in 0..3) {
                dst[k * 2] = verts[nodes[k] * 2]
                dst[k * 2 + 1] = verts[nodes[k] * 2 + 1]
                cx += dst[k * 2]
                cy += dst[k * 2 + 1]
            }
            cx /= 4
            cy /= 4

            src[0] = i * step
            src[1] = j * stepV
            src[2] = (i + 1) * step
            src[3] = j * stepV
            src[4] = (i + 1) * step
            src[5] = (j + 1) * stepV
            src[6] = i * step
            src[7] = (j + 1) * stepV

            if (!matrix.setPolyToPoly(src, 0, dst, 0, 4)) continue

            // Обрезка идёт без сглаживания, поэтому соседние ячейки расходятся
            // на полпикселя и между ними светится фон. Раздвигаем их от центра.
            clip.rewind()
            for (k in 0..3) {
                val x = dst[k * 2] + (dst[k * 2] - cx) * 0.004f + if (dst[k * 2] > cx) 0.7f else -0.7f
                val y =
                    dst[k * 2 + 1] + (dst[k * 2 + 1] - cy) * 0.004f +
                        if (dst[k * 2 + 1] > cy) 0.7f else -0.7f
                if (k == 0) clip.moveTo(x, y) else clip.lineTo(x, y)
            }
            clip.close()

            drawIntoCanvas {
                val canvas = it.nativeCanvas
                val save = canvas.save()
                canvas.clipPath(clip)
                canvas.concat(matrix)
                canvas.drawBitmap(tile.bitmap, 0f, 0f, paint)
                canvas.restoreToCount(save)
            }
        }
    }
}

/** Запасная земля, когда подложки нет: сетка по сто метров. */
private fun DrawScope.drawGroundGrid(
    camera: FlyCamera,
    groundZ: Double,
    palette: FlyPalette,
) {
    val step = 100.0
    val ex = floor(camera.eye.x / step) * step
    val ey = floor(camera.eye.y / step) * step
    val reach = FAR
    val lines = (reach / step).toInt()

    for (k in -lines..lines) {
        val x = ex + k * step
        val y = ey + k * step
        camera.segment(Vec3(x, ey - reach, groundZ), Vec3(x, ey + reach, groundZ))?.let { (a, b) ->
            drawLine(palette.grid, a, b, strokeWidth = 1.dp.toPx())
        }
        camera.segment(Vec3(ex - reach, y, groundZ), Vec3(ex + reach, y, groundZ))?.let { (a, b) ->
            drawLine(palette.grid, a, b, strokeWidth = 1.dp.toPx())
        }
    }
}

/** Столбики на круглых километрах — единственная разметка расстояния в полёте. */
private fun DrawScope.drawMilestones(
    track: RideTrack,
    camera: FlyCamera,
    meters: Double,
    groundZ: Double,
    palette: FlyPalette,
) {
    var km = floor((meters + FAR) / 1000).toInt()
    while (km >= 0 && km * 1000.0 > meters - 60) {
        val at = km * 1000.0
        if (at <= track.totalDistance) {
            val p = track.poseAt(at)
            camera.segment(Vec3(p.x, p.y, groundZ), Vec3(p.x, p.y, p.z + 12.0))?.let { (a, b) ->
                drawLine(palette.milestone.copy(alpha = 0.75f), a, b, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        km--
    }
}

/** Лента трека и стена профиля под ней — от дальнего конца к ближнему. */
private fun DrawScope.drawRibbon(
    track: RideTrack,
    camera: FlyCamera,
    meters: Double,
    groundZ: Double,
    palette: FlyPalette,
) {
    val points = track.points
    if (points.size < 2) return
    val fromIdx = track.indexAtDistance(meters - 200).coerceAtLeast(0)
    val toIdx = track.indexAtDistance(meters + FAR).coerceAtMost(points.lastIndex)
    if (toIdx <= fromIdx) return

    var i = toIdx
    while (i > fromIdx) {
        val b = points[i]
        // Дальний план рисуем реже: там разница между соседними точками меньше
        // пикселя, а сегментов набегают сотни.
        val stride =
            if (b.distance - meters > 400) {
                6
            } else if (b.distance - meters > 150) {
                3
            } else {
                1
            }
        val j = (i - stride).coerceAtLeast(fromIdx)
        val a = points[j]
        i = j

        val dx = b.x - a.x
        val dy = b.y - a.y
        val len = hypot(dx, dy)
        if (len < 1e-3) continue
        val nx = -dy / len * RIBBON_HALF_WIDTH
        val ny = dx / len * RIBBON_HALF_WIDTH

        val al = camera.project(Vec3(a.x + nx, a.y + ny, a.altitude)) ?: continue
        val ar = camera.project(Vec3(a.x - nx, a.y - ny, a.altitude)) ?: continue
        val bl = camera.project(Vec3(b.x + nx, b.y + ny, b.altitude)) ?: continue
        val br = camera.project(Vec3(b.x - nx, b.y - ny, b.altitude)) ?: continue
        val ag = camera.project(Vec3(a.x, a.y, groundZ))
        val bg = camera.project(Vec3(b.x, b.y, groundZ))
        val ac = camera.project(Vec3(a.x, a.y, a.altitude)) ?: continue
        val bc = camera.project(Vec3(b.x, b.y, b.altitude)) ?: continue

        val color = palette.speedColor((a.speedKmh + b.speedKmh) / 2, track.maxSpeed)
        // Дальше — прозрачнее: дешёвая, но убедительная воздушная перспектива.
        val fade = (1.0 - ((b.distance - meters) / FAR).coerceIn(0.0, 1.0)).toFloat()

        if (ag != null && bg != null) {
            // У самой камеры стена профиля разворачивается на пол-экрана и
            // читается как световой клин поперёк кадра — поэтому вблизи её
            // гасим, оставляя только там, где она и правда показывает рельеф.
            val near = ((b.distance - meters) / 150.0).coerceIn(0.0, 1.0).toFloat()
            val curtain =
                Path().apply {
                    moveTo(ag.x, ag.y)
                    lineTo(ac.x, ac.y)
                    lineTo(bc.x, bc.y)
                    lineTo(bg.x, bg.y)
                    close()
                }
            drawPath(curtain, color.copy(alpha = (0.26f * fade + 0.06f) * near))
        }

        val ribbon =
            Path().apply {
                moveTo(al.x, al.y)
                lineTo(bl.x, bl.y)
                lineTo(br.x, br.y)
                lineTo(ar.x, ar.y)
                close()
            }
        drawPath(ribbon, color.copy(alpha = 0.35f + 0.55f * fade))
        drawLine(color, ac, bc, strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
    }
}

/** Метка райдера: столб света от земли и светящийся шар на треке. */
private fun DrawScope.drawRider(
    track: RideTrack,
    camera: FlyCamera,
    meters: Double,
    groundZ: Double,
    palette: FlyPalette,
) {
    val p = track.poseAt(meters)
    val head = Vec3(p.x, p.y, p.z + 1.6)
    val at = camera.project(head) ?: return

    camera.segment(Vec3(p.x, p.y, groundZ), head)?.let { (a, b) ->
        drawLine(palette.marker.copy(alpha = 0.4f), a, b, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    }

    val radius = camera.pixelsPerMeter(head, 2.4).coerceIn(4.dp.toPx(), 26.dp.toPx())
    drawCircle(palette.marker.copy(alpha = 0.20f), radius * 2.6f, at)
    drawCircle(palette.marker.copy(alpha = 0.45f), radius * 1.6f, at)
    drawCircle(palette.marker, radius, at)
    drawCircle(Color.White.copy(alpha = 0.9f), radius * 0.42f, at)
}
