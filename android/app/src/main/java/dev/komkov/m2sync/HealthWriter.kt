package dev.komkov.m2sync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.CyclingPedalingCadenceRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Velocity
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Запись заезда в Health Connect.
 *
 * Дедупликация — на clientRecordId вида "m2:<имя файла>": повторный импорт того же
 * файла обновляет запись, а не плодит дубли.
 */
object HealthWriter {

    /** Больше точек Health Connect в один маршрут не принимает. */
    private const val MAX_ROUTE_POINTS = 1000
    private const val MAX_SAMPLES_PER_RECORD = 1000

    private val DEVICE = Device(
        manufacturer = "CYCPLUS",
        model = "M2",
        type = Device.TYPE_UNKNOWN,
    )

    val permissions: Set<String> = setOf(
        HealthPermission.getWritePermission(ExerciseSessionRecord::class),
        HealthPermission.getWritePermission(HeartRateRecord::class),
        HealthPermission.getWritePermission(DistanceRecord::class),
        HealthPermission.getWritePermission(SpeedRecord::class),
        HealthPermission.getWritePermission(ElevationGainedRecord::class),
        HealthPermission.getWritePermission(CyclingPedalingCadenceRecord::class),
        HealthPermission.getWritePermission(TotalCaloriesBurnedRecord::class),
        HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE,
    )

    /** Самопроверка плюс вес: калории считаем сами, а вес ведёт кто-то другой. */
    val readPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(CyclingPedalingCadenceRecord::class),
        HealthPermission.getReadPermission(SpeedRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
    )

    /**
     * Последний известный вес — на нём стоит весь расчёт калорий.
     * Берём любой источник: весы, Fit, ручной ввод — неважно чей.
     */
    suspend fun readLatestWeight(ctx: Context): Double? =
        client(ctx).readRecords(
            ReadRecordsRequest(
                recordType = WeightRecord::class,
                timeRangeFilter = TimeRangeFilter.before(java.time.Instant.now()),
                ascendingOrder = false,
                pageSize = 1,
            )
        ).records.firstOrNull()?.weight?.inKilograms

    suspend fun readSessions(ctx: Context, since: java.time.Instant): List<ExerciseSessionRecord> =
        client(ctx).readRecords(
            ReadRecordsRequest(
                recordType = ExerciseSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.after(since),
            )
        ).records

    /** Только свои записи: иначе в окно попадает то, что параллельно писал Google Fit. */
    private fun ownOrigin(ctx: Context) = setOf(DataOrigin(ctx.packageName))

    suspend fun readDistanceTotal(ctx: Context, from: java.time.Instant, to: java.time.Instant): Double =
        client(ctx).readRecords(
            ReadRecordsRequest(
                recordType = DistanceRecord::class,
                timeRangeFilter = TimeRangeFilter.between(from, to),
                dataOriginFilter = ownOrigin(ctx),
            )
        ).records.sumOf { it.distance.inMeters }

    suspend fun readHeartRateCount(ctx: Context, from: java.time.Instant, to: java.time.Instant): Int =
        client(ctx).readRecords(
            ReadRecordsRequest(
                recordType = HeartRateRecord::class,
                timeRangeFilter = TimeRangeFilter.between(from, to),
                dataOriginFilter = ownOrigin(ctx),
            )
        ).records.sumOf { it.samples.size }

    suspend fun readCadenceCount(ctx: Context, from: java.time.Instant, to: java.time.Instant): Int =
        client(ctx).readRecords(
            ReadRecordsRequest(
                recordType = CyclingPedalingCadenceRecord::class,
                timeRangeFilter = TimeRangeFilter.between(from, to),
                dataOriginFilter = ownOrigin(ctx),
            )
        ).records.sumOf { it.samples.size }

    suspend fun readSpeedCount(ctx: Context, from: java.time.Instant, to: java.time.Instant): Int =
        client(ctx).readRecords(
            ReadRecordsRequest(
                recordType = SpeedRecord::class,
                timeRangeFilter = TimeRangeFilter.between(from, to),
                dataOriginFilter = ownOrigin(ctx),
            )
        ).records.sumOf { it.samples.size }

    fun available(ctx: Context): Boolean =
        HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE

    fun client(ctx: Context): HealthConnectClient = HealthConnectClient.getOrCreate(ctx)

    suspend fun granted(ctx: Context): Set<String> =
        client(ctx).permissionController.getGrantedPermissions()

    suspend fun write(ctx: Context, ride: FitParser.Ride, weightKg: Double? = null): Int {
        val hc = client(ctx)
        val zone = ZoneId.systemDefault()
        val startOffset: ZoneOffset = zone.rules.getOffset(ride.start)
        val endOffset: ZoneOffset = zone.rules.getOffset(ride.end)
        val id = "m2:${ride.fileName}"

        fun meta(suffix: String) = Metadata.autoRecorded(DEVICE, "$id:$suffix")

        val records = ArrayList<Record>()

        val route = ride.points
            .filter { it.lat != null && it.lon != null }
            .downsample(MAX_ROUTE_POINTS)
            .map {
                ExerciseRoute.Location(
                    time = it.time,
                    latitude = it.lat!!,
                    longitude = it.lon!!,
                    altitude = it.altitude?.let { a -> Length.meters(a) },
                )
            }

        // Сессия занимает всё время заезда, а остановки размечаем паузами —
        // тогда «активная длительность» в Health Connect совпадёт со временем в движении.
        val segments = ArrayList<ExerciseSegment>()
        ride.activeSpans.forEachIndexed { i, span ->
            if (i > 0) {
                val gapStart = ride.activeSpans[i - 1].second
                if (gapStart.isBefore(span.first)) {
                    segments += ExerciseSegment(
                        startTime = gapStart,
                        endTime = span.first,
                        segmentType = ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE,
                    )
                }
            }
            segments += ExerciseSegment(
                startTime = span.first,
                endTime = span.second,
                segmentType = ExerciseSegment.EXERCISE_SEGMENT_TYPE_BIKING,
            )
        }

        records += ExerciseSessionRecord(
            startTime = ride.start,
            startZoneOffset = startOffset,
            endTime = ride.end,
            endZoneOffset = endOffset,
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
            title = ctx.getString(R.string.session_title),
            notes = ride.fileName,
            metadata = Metadata.autoRecorded(DEVICE, id),
            segments = segments.filter {
                !it.startTime.isBefore(ride.start) && !it.endTime.isAfter(ride.end)
            },
            exerciseRoute = if (route.isNotEmpty()) ExerciseRoute(route) else null,
        )

        ride.totalDistance?.takeIf { it > 0 }?.let {
            records += DistanceRecord(
                startTime = ride.start, startZoneOffset = startOffset,
                endTime = ride.end, endZoneOffset = endOffset,
                distance = Length.meters(it),
                metadata = meta("distance"),
            )
        }

        Calories.forRide(ride, weightKg)?.let {
            records += TotalCaloriesBurnedRecord(
                startTime = ride.start, startZoneOffset = startOffset,
                endTime = ride.end, endZoneOffset = endOffset,
                energy = Energy.kilocalories(it.toDouble()),
                metadata = meta("calories"),
            )
        }

        ride.totalAscent?.takeIf { it > 0 }?.let {
            records += ElevationGainedRecord(
                startTime = ride.start, startZoneOffset = startOffset,
                endTime = ride.end, endZoneOffset = endOffset,
                elevation = Length.meters(it.toDouble()),
                metadata = meta("ascent"),
            )
        }

        val hrPoints = ride.points.filter { (it.heartRate ?: 0) > 0 }
        hrPoints.chunked(MAX_SAMPLES_PER_RECORD).forEachIndexed { i, chunk ->
            records += HeartRateRecord(
                startTime = chunk.first().time, startZoneOffset = startOffset,
                endTime = chunk.last().time.plusSeconds(1), endZoneOffset = endOffset,
                samples = chunk.map {
                    HeartRateRecord.Sample(it.time, it.heartRate!!.toLong())
                },
                metadata = meta("hr$i"),
            )
        }

        val cadencePoints = ride.points.filter { (it.cadence ?: 0) > 0 }
        cadencePoints.chunked(MAX_SAMPLES_PER_RECORD).forEachIndexed { i, chunk ->
            records += CyclingPedalingCadenceRecord(
                startTime = chunk.first().time, startZoneOffset = startOffset,
                endTime = chunk.last().time.plusSeconds(1), endZoneOffset = endOffset,
                samples = chunk.map {
                    CyclingPedalingCadenceRecord.Sample(it.time, it.cadence!!.toDouble())
                },
                metadata = meta("cadence$i"),
            )
        }

        val speedPoints = ride.points.filter { (it.speed ?: 0.0) > 0.0 }
        speedPoints.chunked(MAX_SAMPLES_PER_RECORD).forEachIndexed { i, chunk ->
            records += SpeedRecord(
                startTime = chunk.first().time, startZoneOffset = startOffset,
                endTime = chunk.last().time.plusSeconds(1), endZoneOffset = endOffset,
                samples = chunk.map {
                    SpeedRecord.Sample(it.time, Velocity.metersPerSecond(it.speed!!))
                },
                metadata = meta("speed$i"),
            )
        }

        hc.insertRecords(records)
        return records.size
    }

    private fun <T> List<T>.downsample(max: Int): List<T> {
        if (size <= max) return this
        val step = size.toDouble() / max
        return (0 until max).map { this[(it * step).toInt()] }
    }
}
