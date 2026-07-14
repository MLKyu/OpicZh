package com.mingeek.opiczh.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * 자동 텍스트 모델 체인 상태 저장소.
 * - chain: models.list에서 서열화한 모델 ID 목록 (좋은 모델부터). 사용자가 고르지 않는다.
 * - cooldowns: 모델별 "이 시각(epoch ms)까지 한도 초과" 기록. 429를 만나면 기록하고,
 *   그 시각 전에는 해당 모델을 건너뛰어 다음 모델로 자동 전환한다.
 * 프로세스가 죽어도 일일 한도(RPD) 쿨다운이 유지되도록 DataStore에 영속화한다.
 */
@Singleton
class ModelChainStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    private object Keys {
        val CHAIN_JSON = stringPreferencesKey("text_model_chain")
        val CHAIN_FETCHED_AT = longPreferencesKey("text_model_chain_fetched_at")
        val COOLDOWNS_JSON = stringPreferencesKey("model_cooldowns")
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val chainSerializer = ListSerializer(String.serializer())
    private val cooldownSerializer = MapSerializer(String.serializer(), Long.serializer())

    /** 서열화된 모델 체인. 아직 조회 전이면 빈 목록. */
    val chain: Flow<List<String>> = dataStore.data.map { prefs ->
        prefs[Keys.CHAIN_JSON]?.let { raw ->
            runCatching { json.decodeFromString(chainSerializer, raw) }.getOrNull()
        }.orEmpty()
    }.distinctUntilChanged()

    /** 체인을 마지막으로 갱신한 시각 (epoch ms) */
    val chainFetchedAt: Flow<Long?> = dataStore.data.map { prefs ->
        prefs[Keys.CHAIN_FETCHED_AT]
    }.distinctUntilChanged()

    /** 모델 ID → 한도 해제 예상 시각(epoch ms). 지난 항목은 저장 시 정리된다. */
    val cooldowns: Flow<Map<String, Long>> = dataStore.data.map { prefs ->
        prefs[Keys.COOLDOWNS_JSON]?.let { raw ->
            runCatching { json.decodeFromString(cooldownSerializer, raw) }.getOrNull()
        }.orEmpty()
    }.distinctUntilChanged()

    suspend fun saveChain(models: List<String>, fetchedAtMs: Long) {
        dataStore.edit { prefs ->
            prefs[Keys.CHAIN_JSON] = json.encodeToString(chainSerializer, models)
            prefs[Keys.CHAIN_FETCHED_AT] = fetchedAtMs
        }
    }

    /** 모델의 한도 초과를 기록한다. 만료된 다른 모델의 기록은 함께 정리한다. */
    suspend fun setCooldown(modelId: String, untilEpochMs: Long, nowMs: Long) {
        dataStore.edit { prefs ->
            val current = prefs[Keys.COOLDOWNS_JSON]?.let { raw ->
                runCatching { json.decodeFromString(cooldownSerializer, raw) }.getOrNull()
            }.orEmpty()
            val updated = (current + (modelId to untilEpochMs)).filterValues { it > nowMs }
            prefs[Keys.COOLDOWNS_JSON] = json.encodeToString(cooldownSerializer, updated)
        }
    }
}
