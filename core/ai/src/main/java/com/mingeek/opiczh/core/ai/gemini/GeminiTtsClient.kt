package com.mingeek.opiczh.core.ai.gemini

import com.mingeek.opiczh.core.common.AppError
import com.mingeek.opiczh.core.common.AppResult
import com.mingeek.opiczh.core.common.WavCodec
import com.mingeek.opiczh.core.common.retryWithBackoff
import com.mingeek.opiczh.core.data.settings.SettingsRepository
import com.mingeek.opiczh.core.speech.RemoteTtsSynthesizer
import java.security.MessageDigest
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

/**
 * Gemini TTS(자연 음성 합성) 클라이언트. 원시 PCM(L16) 응답을 WAV로 감싸 반환한다.
 * core:speech의 [RemoteTtsSynthesizer] 구현체 — ChineseSpeaker가 사용한다.
 */
@Singleton
class GeminiTtsClient @Inject constructor(
    private val api: GeminiApi,
    private val apiKeyHolder: ApiKeyHolder,
    private val settingsRepository: SettingsRepository,
    private val json: Json,
) : RemoteTtsSynthesizer {

    override suspend fun cacheKey(text: String): String {
        val settings = settingsRepository.settings.first()
        val raw = "${settings.ttsModelId}|${settings.ttsVoice}|$text"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    override suspend fun synthesize(text: String): AppResult<ByteArray> {
        if (apiKeyHolder.current.isNullOrBlank()) {
            return AppResult.failure(AppError.ApiKeyMissing)
        }
        val settings = settingsRepository.settings.first()
        val body = GenerateContentRequest(
            contents = listOf(Content(parts = listOf(Part(text = text)), role = "user")),
            generationConfig = GenerationConfig(
                responseModalities = listOf("AUDIO"),
                speechConfig = SpeechConfig(
                    voiceConfig = VoiceConfig(
                        prebuiltVoiceConfig = PrebuiltVoiceConfig(voiceName = settings.ttsVoice),
                    ),
                ),
            ),
        )
        return retryWithBackoff { call(settings.ttsModelId, body) }
    }

    private suspend fun call(model: String, body: GenerateContentRequest): AppResult<ByteArray> =
        try {
            val response = api.generateContent(model, body)
            if (response.isSuccessful) {
                extractWav(response.body())
            } else {
                AppResult.failure(
                    GeminiErrorMapper.fromResponse(
                        response.code(),
                        response.errorBody()?.string(),
                        json,
                    ),
                )
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            AppResult.failure(GeminiErrorMapper.fromThrowable(t))
        }

    private fun extractWav(body: GenerateContentResponse?): AppResult<ByteArray> {
        val audioPart = body?.candidates?.firstOrNull()?.content?.parts.orEmpty()
            .firstOrNull { it.inlineData != null }
            ?.inlineData
            ?: return AppResult.failure(AppError.Parsing("TTS 응답에 오디오가 없습니다"))
        return try {
            val pcm = Base64.getDecoder().decode(audioPart.data)
            val sampleRate = WavCodec.sampleRateFromMime(audioPart.mimeType)
            AppResult.success(WavCodec.pcmToWav(pcm, sampleRate))
        } catch (t: Throwable) {
            AppResult.failure(AppError.Parsing("TTS 오디오 디코딩 실패: ${t.message}"))
        }
    }
}
