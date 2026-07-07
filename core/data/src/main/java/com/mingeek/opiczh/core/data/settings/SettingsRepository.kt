package com.mingeek.opiczh.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
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
) {

    private object Keys {
        val API_KEY_ENCRYPTED = stringPreferencesKey("api_key_encrypted")
        val HF_TOKEN_ENCRYPTED = stringPreferencesKey("hf_token_encrypted")
        val TARGET_GRADE = stringPreferencesKey("target_grade")
        val TEXT_MODEL_ID = stringPreferencesKey("text_model_id")
        val TTS_MODEL_ID = stringPreferencesKey("tts_model_id")
        val TTS_VOICE = stringPreferencesKey("tts_voice")
        val ROUTING_POLICY = stringPreferencesKey("routing_policy")
    }

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            hasApiKey = !prefs[Keys.API_KEY_ENCRYPTED].isNullOrBlank(),
            targetGrade = prefs[Keys.TARGET_GRADE]?.let { name ->
                TargetGrade.entries.firstOrNull { it.name == name }
            } ?: TargetGrade.DEFAULT,
            textModelId = prefs[Keys.TEXT_MODEL_ID] ?: DefaultModels.TEXT,
            ttsModelId = prefs[Keys.TTS_MODEL_ID] ?: DefaultModels.TTS,
            ttsVoice = prefs[Keys.TTS_VOICE] ?: DefaultModels.TTS_VOICE,
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

    /** HuggingFace 토큰 (게이트 모델 다운로드용, 암호화 저장) */
    val hfToken: Flow<String?> = dataStore.data.map { prefs ->
        prefs[Keys.HF_TOKEN_ENCRYPTED]?.takeIf { it.isNotBlank() }?.let(cipher::decrypt)
    }.distinctUntilChanged()

    suspend fun setHfToken(rawToken: String) {
        val encrypted = cipher.encrypt(rawToken.trim())
        dataStore.edit { it[Keys.HF_TOKEN_ENCRYPTED] = encrypted }
    }

    suspend fun clearHfToken() {
        dataStore.edit { it.remove(Keys.HF_TOKEN_ENCRYPTED) }
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
