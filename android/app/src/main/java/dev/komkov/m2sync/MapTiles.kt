package dev.komkov.m2sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisallowComposableCalls
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Растровый тайл. Плоской карте нужен [image], полёту — [bitmap]: перспективу
 * землю натягивает `drawBitmapMesh`, а он работает только с андроидным Bitmap.
 */
class MapTile(
    val bitmap: Bitmap,
) {
    val image: ImageBitmap by lazy { bitmap.asImageBitmap() }
}

/**
 * Чем застилать землю под треком.
 *
 * Оба источника отдают тайлы без ключа и без учётки — иначе им здесь было бы не
 * место: приложение тем и держится, что не требует ни того, ни другого. Наружу
 * уходит только номер квадрата, не координаты заезда и не сам трек.
 */
enum class MapLayer(
    /** Шаблон адреса; null — подложки нет, рисуем только трек на сетке. */
    val url: String?,
    val attribution: String?,
    val label: Int,
) {
    NONE(null, null, R.string.layer_none),

    MAP(
        "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        "© OpenStreetMap",
        R.string.layer_map,
    ),

    // World Imagery отдаёт снимки в порядке {z}/{y}/{x} — не как у всех.
    SATELLITE(
        "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
        "Esri, Maxar, Earthstar Geographics",
        R.string.layer_satellite,
    ),
    ;

    val enabled: Boolean get() = url != null

    /** Следующая по кругу: одной кнопкой перебираем все три состояния. */
    fun next(): MapLayer = entries[(ordinal + 1) % entries.size]

    fun urlOf(
        z: Int,
        x: Int,
        y: Int,
    ): String? =
        url
            ?.replace("{z}", z.toString())
            ?.replace("{x}", x.toString())
            ?.replace("{y}", y.toString())
}

/**
 * Подложка карты: растровые тайлы, кэш в памяти и на диске.
 *
 * Второй просмотр заезда сети уже не требует, а [prefetch] прогревает весь
 * маршрут заранее — в полёте дыры на месте недокачанных квадратов особенно
 * заметны.
 */
@Stable
class TileSource(
    private val ctx: Context,
    private val scope: CoroutineScope,
) {
    companion object {
        /** Политика OSM требует опознаваемый User-Agent — иначе отдают 403. */
        private val USER_AGENT =
            "m2sync/${BuildConfig.VERSION_NAME} (+https://github.com/${UpdateChecker.REPO})"

        /** 256×256 ARGB — 256 КБ на тайл, так что держим в памяти немного. */
        private const val MEMORY_TILES = 96

        /** Больше четырёх параллельных запросов OSM просит не открывать. */
        private const val PARALLEL_REQUESTS = 4

        /** Потолок прогрева: длинный заезд иначе утянет сотни мегабайт. */
        const val PREFETCH_LIMIT = 700

        fun key(
            layer: MapLayer,
            z: Int,
            x: Int,
            y: Int,
        ): Long = (layer.ordinal.toLong() shl 49) or (z.toLong() shl 44) or (x.toLong() shl 22) or y.toLong()
    }

    private val memory =
        object : LinkedHashMap<Long, MapTile>(MEMORY_TILES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, MapTile>) = size > MEMORY_TILES
        }
    private val pending = HashSet<Long>()

    /** Что не доехало — не просим повторно: без сети это был бы вечный цикл. */
    private val failed = HashSet<Long>()
    private val gate = Semaphore(PARALLEL_REQUESTS)

    /**
     * Растёт на каждый доехавший тайл. Чтение внутри draw-лямбды подписывает
     * холст на обновления, поэтому карта дорисовывается сама.
     */
    var revision by mutableIntStateOf(0)
        private set

    /** Готовый тайл или null — тогда он поехал загружаться и придёт позже. */
    fun tile(
        layer: MapLayer,
        z: Int,
        x: Int,
        y: Int,
    ): MapTile? {
        if (!layer.enabled) return null
        val span = 1 shl z
        if (z < GeoBounds.MIN_ZOOM || z > GeoBounds.MAX_ZOOM) return null
        if (x < 0 || y < 0 || x >= span || y >= span) return null
        val key = key(layer, z, x, y)
        memory[key]?.let { return it }
        if (key !in failed && pending.add(key)) scope.launch { load(layer, key, z, x, y) }
        return null
    }

    /**
     * Заранее тянет квадраты вдоль всего маршрута.
     *
     * Кадр запрашивает только то, что видно прямо сейчас, и на скорости карта не
     * успевает: впереди зияют дыры. Прогрев идёт в порядке движения, так что
     * начало заезда готово раньше, чем до него долетели.
     */
    fun prefetch(
        layer: MapLayer,
        z: Int,
        tiles: List<Long>,
    ) {
        if (!layer.enabled) return
        scope.launch {
            for (packed in tiles.take(PREFETCH_LIMIT)) {
                val x = (packed shr 32).toInt()
                val y = packed.toInt()
                val key = key(layer, z, x, y)
                if (memory[key] != null || key in failed || !pending.add(key)) continue
                load(layer, key, z, x, y)
            }
        }
    }

    private suspend fun load(
        layer: MapLayer,
        key: Long,
        z: Int,
        x: Int,
        y: Int,
    ) {
        val bitmap =
            withContext(Dispatchers.IO) {
                val file = File(ctx.cacheDir, "tiles/${layer.name.lowercase()}/$z/$x/$y.png")
                val bytes =
                    if (file.isFile) {
                        runCatching { file.readBytes() }.getOrNull()
                    } else {
                        gate.withPermit { download(layer, z, x, y, file) }
                    }
                bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
            }
        pending.remove(key)
        if (bitmap == null) {
            failed += key
        } else {
            memory[key] = MapTile(bitmap)
            revision++
        }
    }

    private fun download(
        layer: MapLayer,
        z: Int,
        x: Int,
        y: Int,
        target: File,
    ): ByteArray? =
        runCatching {
            val address = layer.urlOf(z, x, y) ?: return null
            val connection =
                (URL(address).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    setRequestProperty("User-Agent", USER_AGENT)
                }
            try {
                val bytes = connection.inputStream.use { it.readBytes() }
                target.parentFile?.mkdirs()
                runCatching { target.writeBytes(bytes) }
                bytes
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
}

@Composable
fun rememberTileSource(): TileSource {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(ctx, scope) { TileSource(ctx, scope) }
}

/**
 * Обходит тайлы, накрывающие прямоугольник в координатах тайловой сетки.
 *
 * Отдаёт номер как есть: рисовать надо на исходном месте, а вот запрашивать — по
 * завёрнутому номеру, для чего есть [wrapTileX]. Общая часть карты и полёта.
 */
inline fun forEachTile(
    z: Int,
    minTileX: Double,
    maxTileX: Double,
    minTileY: Double,
    maxTileY: Double,
    body: @DisallowComposableCalls (x: Int, y: Int) -> Unit,
) {
    val span = 1 shl z
    val x0 = kotlin.math.floor(minTileX).toInt()
    val x1 = kotlin.math.floor(maxTileX).toInt()
    val y0 =
        kotlin.math
            .floor(minTileY)
            .toInt()
            .coerceAtLeast(0)
    val y1 =
        kotlin.math
            .floor(maxTileY)
            .toInt()
            .coerceAtMost(span - 1)
    for (ty in y0..y1) {
        for (tx in x0..x1) body(tx, ty)
    }
}

/** По долготе мир заворачивается: тайл −1 на зуме 4 — это тайл 15. */
fun wrapTileX(
    x: Int,
    z: Int,
): Int {
    val span = 1 shl z
    return ((x % span) + span) % span
}

/**
 * Квадраты, накрывающие маршрут, в порядке движения.
 *
 * [ring] добавляет кольцо соседей вокруг каждого: камера смотрит и по сторонам,
 * и одной ниткой вдоль трека кадр не закрыть. Номера пакуются в Long, чтобы
 * список из сотен пар не плодил объектов.
 */
fun routeTiles(
    track: RideTrack,
    zoom: Int,
    ring: Int = 1,
): List<Long> {
    val seen = LinkedHashSet<Long>()
    for (p in track.points) {
        val tx = Geo.tileX(p.lon, zoom).toInt()
        val ty = Geo.tileY(p.lat, zoom).toInt()
        for (dy in -ring..ring) {
            for (dx in -ring..ring) {
                val x = wrapTileX(tx + dx, zoom)
                val y = ty + dy
                if (y < 0 || y >= (1 shl zoom)) continue
                seen += (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
            }
        }
    }
    return seen.toList()
}
