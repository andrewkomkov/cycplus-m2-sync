package dev.komkov.m2sync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.aggregate.AggregationResultGroupedByDuration
import androidx.health.connect.client.aggregate.AggregationResultGroupedByPeriod
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.request.AggregateGroupByDurationRequest
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ChangesTokenRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ChangesResponse
import androidx.health.connect.client.response.InsertRecordsResponse
import androidx.health.connect.client.response.ReadRecordResponse
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.time.TimeRangeFilter
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import kotlin.reflect.KClass

// Подменыши Health Connect для тестов HealthWriter.
//
// Настоящий клиент создаётся статикой `HealthConnectClient.getOrCreate`, а она
// лезет к системному провайдеру, которого в JVM нет. Шва в продакшн-коде нет,
// поэтому перехватываем саму статику шэдоу Robolectric и отдаём фейк.

/** Тень над `HealthConnectClient.Companion`: и статус SDK, и клиент задаёт тест. */
@Implements(
    className = "androidx.health.connect.client.HealthConnectClient\$Companion",
    isInAndroidSdk = false,
)
class ShadowHealthConnectCompanion {
    @Implementation
    protected fun getSdkStatus(
        @Suppress("UNUSED_PARAMETER") context: Context,
        @Suppress("UNUSED_PARAMETER") providerPackageName: String,
    ): Int = sdkStatus

    @Implementation
    protected fun getOrCreate(
        @Suppress("UNUSED_PARAMETER") context: Context,
        @Suppress("UNUSED_PARAMETER") providerPackageName: String,
    ): HealthConnectClient = client ?: throw IllegalStateException("Service not available")

    companion object {
        var sdkStatus: Int = HealthConnectClient.SDK_AVAILABLE
        var client: HealthConnectClient? = null

        fun reset() {
            sdkStatus = HealthConnectClient.SDK_AVAILABLE
            client = null
        }
    }
}

/** Фейковый клиент: отдаёт заранее разложенные записи и запоминает всё, что в него пишут. */
@Suppress("TooManyFunctions")
class FakeHealthConnectClient : HealthConnectClient {
    /** Что вернуть на чтение — по типу записи. */
    val stored: MutableMap<KClass<out Record>, List<Record>> = mutableMapOf()

    /** Все запросы на чтение по порядку: тест сверяет фильтры. */
    val readRequests: MutableList<ReadRecordsRequest<*>> = mutableListOf()

    /** Всё, что писали через insertRecords. */
    val inserted: MutableList<Record> = mutableListOf()

    var featureStatus: Int = HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
    var grantedPermissions: Set<String> = emptySet()

    override val features: HealthConnectFeatures =
        object : HealthConnectFeatures {
            override fun getFeatureStatus(feature: Int): Int = featureStatus
        }

    override val permissionController: PermissionController =
        object : PermissionController {
            override suspend fun getGrantedPermissions(): Set<String> = grantedPermissions

            override suspend fun revokeAllPermissions() = Unit
        }

    override suspend fun insertRecords(records: List<Record>): InsertRecordsResponse {
        inserted += records
        return InsertRecordsResponse(records.map { it.metadata.clientRecordId ?: "" })
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Record> readRecords(request: ReadRecordsRequest<T>): ReadRecordsResponse<T> {
        readRequests += request
        return ReadRecordsResponse(stored[request.recordType].orEmpty() as List<T>, null)
    }

    // --- остальное API интерфейса тестам не нужно ---

    override suspend fun updateRecords(records: List<Record>) = unused()

    override suspend fun deleteRecords(
        recordType: KClass<out Record>,
        recordIdsList: List<String>,
        clientRecordIdsList: List<String>,
    ) = unused()

    override suspend fun deleteRecords(
        recordType: KClass<out Record>,
        timeRangeFilter: TimeRangeFilter,
    ) = unused()

    override suspend fun <T : Record> readRecord(
        recordType: KClass<T>,
        recordId: String,
    ): ReadRecordResponse<T> = unused()

    override suspend fun aggregate(request: AggregateRequest): AggregationResult = unused()

    override suspend fun aggregateGroupByDuration(request: AggregateGroupByDurationRequest): List<AggregationResultGroupedByDuration> =
        unused()

    override suspend fun aggregateGroupByPeriod(request: AggregateGroupByPeriodRequest): List<AggregationResultGroupedByPeriod> = unused()

    override suspend fun getChangesToken(request: ChangesTokenRequest): String = unused()

    override suspend fun getChanges(changesToken: String): ChangesResponse = unused()

    private fun unused(): Nothing = throw UnsupportedOperationException("тестам этот вызов не нужен")
}
