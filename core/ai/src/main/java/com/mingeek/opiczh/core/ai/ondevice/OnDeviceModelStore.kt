package com.mingeek.opiczh.core.ai.ondevice

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mingeek.opiczh.core.ai.di.OnDeviceModelsDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * 온디바이스 모델 상태 영속화:
 * - active: 엔진이 사용하는 현재 모델
 * - pending: 교체를 위해 다운로드 중인 모델 (완료 시 reconcile이 active로 승격 + 구모델 삭제)
 * - recommendation: 마지막 추천 결과
 */
@Singleton
class OnDeviceModelStore @Inject constructor(
    @param:OnDeviceModelsDataStore private val dataStore: DataStore<Preferences>,
) {

    private val json = Json { ignoreUnknownKeys = true }

    private object Keys {
        val ACTIVE = stringPreferencesKey("active_spec")
        val PENDING = stringPreferencesKey("pending_spec")
        val RECOMMENDATION = stringPreferencesKey("recommendation_record")
    }

    val active: Flow<OnDeviceModelSpec?> = dataStore.data.map { prefs ->
        prefs[Keys.ACTIVE]?.let(::decodeSpec)
    }.distinctUntilChanged()

    val pending: Flow<OnDeviceModelSpec?> = dataStore.data.map { prefs ->
        prefs[Keys.PENDING]?.let(::decodeSpec)
    }.distinctUntilChanged()

    val recommendation: Flow<RecommendationRecord?> = dataStore.data.map { prefs ->
        prefs[Keys.RECOMMENDATION]?.let { raw ->
            runCatching { json.decodeFromString<RecommendationRecord>(raw) }.getOrNull()
        }
    }.distinctUntilChanged()

    suspend fun setActive(spec: OnDeviceModelSpec) {
        dataStore.edit { it[Keys.ACTIVE] = json.encodeToString(OnDeviceModelSpec.serializer(), spec) }
    }

    suspend fun clearActiveIfFile(fileName: String) {
        dataStore.edit { prefs ->
            val current = prefs[Keys.ACTIVE]?.let(::decodeSpec)
            if (current?.fileName == fileName) prefs.remove(Keys.ACTIVE)
        }
    }

    suspend fun setPending(spec: OnDeviceModelSpec) {
        dataStore.edit { it[Keys.PENDING] = json.encodeToString(OnDeviceModelSpec.serializer(), spec) }
    }

    suspend fun clearPending() {
        dataStore.edit { it.remove(Keys.PENDING) }
    }

    suspend fun setRecommendation(record: RecommendationRecord) {
        dataStore.edit {
            it[Keys.RECOMMENDATION] = json.encodeToString(RecommendationRecord.serializer(), record)
        }
    }

    private fun decodeSpec(raw: String): OnDeviceModelSpec? =
        runCatching { json.decodeFromString<OnDeviceModelSpec>(raw) }.getOrNull()
}
