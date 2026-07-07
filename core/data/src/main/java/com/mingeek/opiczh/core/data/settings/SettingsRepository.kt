package com.mingeek.opiczh.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mingeek.opiczh.core.common.RemoteTuning
import com.mingeek.opiczh.core.data.security.ApiKeyCipher
import com.mingeek.opiczh.core.model.AppSettings
import com.mingeek.opiczh.core.model.DefaultModels
import com.mingeek.opiczh.core.model.RoutingPolicy
import com.mingeek.opiczh.core.model.TargetGrade
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** 앱 전역 설정. API 키는 Keystore로 암호화되어 저장된다. */
@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val cipher: ApiKeyCipher,
    private val remoteTuning: RemoteTuning,
) {

    private object Keys {
        val API_KEY_ENCRYPTED = stringPreferencesKey("api_key_encrypted")
        val TARGET_GRADE = stringPreferencesKey("target_grade")
        val TEXT_MODEL_ID = stringPreferencesKey("text_model_id")
        val TTS_MODEL_ID = stringPreferencesKey("tts_model_id")
        val TTS_VOICE = stringPreferencesKey("tts_voice")
        val ROUTING_POLICY = stringPreferencesKey("routing_policy")
        val LAST_BACKUP_AT = longPreferencesKey("last_backup_at")
    }

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            hasApiKey = !prefs[Keys.API_KEY_ENCRYPTED].isNullOrBlank(),
            targetGrade = prefs[Keys.TARGET_GRADE]?.let { name ->
                TargetGrade.entries.firstOrNull { it.name == name }
            } ?: TargetGrade.DEFAULT,
            // 우선순위: 사용자가 직접 고른 값 > Remote Config 원격 기본값 > 하드코딩 기본값
            textModelId = prefs[Keys.TEXT_MODEL_ID]
                ?: remoteTuning.string(RemoteTuning.Keys.TEXT_MODEL_DEFAULT)
                ?: DefaultModels.TEXT,
            ttsModelId = prefs[Keys.TTS_MODEL_ID]
                ?: remoteTuning.string(RemoteTuning.Keys.TTS_MODEL_DEFAULT)
                ?: DefaultModels.TTS,
            ttsVoice = prefs[Keys.TTS_VOICE]
                ?: remoteTuning.string(RemoteTuning.Keys.TTS_VOICE_DEFAULT)
                ?: DefaultModels.TTS_VOICE,
            routingPolicy = prefs[Keys.ROUTING_POLICY]?.let { name ->
                RoutingPolicy.entries.firstOrNull { it.name == name }
            } ?: RoutingPolicy.AUTO,
        )
    }.distinctUntilChanged()

    /** 복호화된 API 키. 미등록/복호화 실패 시 null. */
    val apiKey: Flow<String?> = dataStore.data.map { prefs ->
        prefs[Keys.API_KEY_ENCRYPTED]?.takeIf { it.isNotBlank() }?.let(cipher::decrypt)
    }.distinctUntilChanged()

    suspend fun setApiKey(rawKey: String) {
        val encrypted = cipher.encrypt(rawKey.trim())
        dataStore.edit { it[Keys.API_KEY_ENCRYPTED] = encrypted }
    }

    suspend fun clearApiKey() {
        dataStore.edit { it.remove(Keys.API_KEY_ENCRYPTED) }
    }

    /** 마지막 백업 내보내기 완료 시각 (epoch ms) */
    val lastBackupAt: Flow<Long?> = dataStore.data.map { prefs ->
        prefs[Keys.LAST_BACKUP_AT]
    }.distinctUntilChanged()

    suspend fun markBackupDone() {
        dataStore.edit { it[Keys.LAST_BACKUP_AT] = System.currentTimeMillis() }
    }

    suspend fun setTargetGrade(grade: TargetGrade) {
        dataStore.edit { it[Keys.TARGET_GRADE] = grade.name }
    }

    suspend fun setTextModelId(modelId: String) {
        dataStore.edit { it[Keys.TEXT_MODEL_ID] = modelId.trim() }
    }

    suspend fun setTtsModelId(modelId: String) {
        dataStore.edit { it[Keys.TTS_MODEL_ID] = modelId.trim() }
    }

    suspend fun setTtsVoice(voice: String) {
        dataStore.edit { it[Keys.TTS_VOICE] = voice.trim() }
    }

    suspend fun setRoutingPolicy(policy: RoutingPolicy) {
        dataStore.edit { it[Keys.ROUTING_POLICY] = policy.name }
    }
}
