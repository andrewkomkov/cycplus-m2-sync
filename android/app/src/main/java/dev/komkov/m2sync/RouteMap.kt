package dev.komkov.m2sync

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

/**
 * Плоская карта заезда: подложка OpenStreetMap и трек поверх неё.
 *
 * Зум подбирается так, чтобы маршрут влез целиком — заезд смотрят как единое
 * целое, а не возят по нему пальцем, поэтому жестов здесь намеренно нет.
 */
@Composable
fun RouteMap(
    track: RideTrack,
    tiles: TileSource,
    modifier: Modifier = Modifier,
    highlight: Int? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val dark = isSystemInDarkTheme()
    val bounds = track.bounds
    val layer by Settings.mapLayer.collectAsStateWithLifecycle()

    // Тайл 256×256 на плотном экране вышел бы с нечитаемыми подписями, поэтому
    // растягиваем вдвое: чуть мягче, зато карта читается.
    val tileScale = 2

    // Подложку приглушаем: у стандартного стиля OSM свои яркие цвета, и трек на
    // нём теряется. Заодно карта перестаёт спорить с палитрой Material You.
    val filter =
        remember(dark, layer) {
            ColorFilter.colorMatrix(
                ColorMatrix().apply {
                    // Снимок и без того натуральный, а схему приглушаем сильнее:
                    // её собственные цвета спорят с палитрой Material You.
                    setToSaturation(if (layer == MapLayer.SATELLITE) 0.85f else 0.4f)
                    val k = if (dark) 0.55f else 0.95f
                    timesAssign(
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

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(scheme.surfaceContainerHighest)
            if (bounds == null || track.points.size < 2) return@Canvas

            val tilePx = Geo.TILE_PX * tileScale
            val zoom = bounds.fitZoom(size.width / tileScale, size.height / tileScale)
            val centerX = Geo.tileX(bounds.centerLon, zoom)
            val centerY = Geo.tileY(bounds.centerLat, zoom)

            fun screenX(lon: Double) = ((Geo.tileX(lon, zoom) - centerX) * tilePx + size.width / 2).toFloat()

            fun screenY(lat: Double) = ((Geo.tileY(lat, zoom) - centerY) * tilePx + size.height / 2).toFloat()

            // Подписка на приезжающие тайлы: чтение состояния в draw-фазе
            // перерисует холст, когда картинка догрузится. Условие ложно всегда,
            // важно само чтение.
            if (tiles.revision < 0) return@Canvas

            forEachTile(
                z = zoom,
                minTileX = centerX - (size.width / 2) / tilePx,
                maxTileX = centerX + (size.width / 2) / tilePx,
                minTileY = centerY - (size.height / 2) / tilePx,
                maxTileY = centerY + (size.height / 2) / tilePx,
            ) { tx, ty ->
                val tile = tiles.tile(layer, zoom, wrapTileX(tx, zoom), ty)
                if (tile != null) {
                    val left = ((tx - centerX) * tilePx + size.width / 2).roundToInt()
                    val top = ((ty - centerY) * tilePx + size.height / 2).roundToInt()
                    drawImage(
                        image = tile.image,
                        dstOffset = IntOffset(left, top),
                        // Плюс пиксель: без него между тайлами видны швы округления.
                        dstSize = IntSize(tilePx + 1, tilePx + 1),
                        colorFilter = filter,
                    )
                }
            }

            val path = Path()
            track.points.forEachIndexed { i, p ->
                val x = screenX(p.lon)
                val y = screenY(p.lat)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }

            // Обводка под линией: на пёстрой подложке трек без неё сливается.
            drawPath(
                path,
                color = scheme.surface.copy(alpha = 0.85f),
                style = Stroke(width = 11.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            drawPath(
                path,
                color = scheme.primary,
                style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )

            val first = track.points.first()
            val last = track.points.last()
            marker(Offset(screenX(first.lon), screenY(first.lat)), scheme.tertiary, scheme.surface)
            marker(Offset(screenX(last.lon), screenY(last.lat)), scheme.primary, scheme.surface)

            track.points.getOrNull(highlight ?: -1)?.let { p ->
                val at = Offset(screenX(p.lon), screenY(p.lat))
                drawCircle(scheme.primary.copy(alpha = 0.22f), 18.dp.toPx(), at)
                marker(at, scheme.secondary, scheme.surface, radius = 7.dp.toPx())
            }
        }

        layer.attribution?.let { credit ->
            // Условие лицензий обоих источников: указывать, чьи это данные.
            Text(
                credit,
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant.copy(alpha = 0.75f),
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
            )
        }
    }
}

private fun DrawScope.marker(
    at: Offset,
    fill: Color,
    ring: Color,
    radius: Float = 6.dp.toPx(),
) {
    drawCircle(ring, radius + 3.dp.toPx(), at)
    drawCircle(fill, radius, at)
}
