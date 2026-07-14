package com.mingeek.opiczh.core.ai.gemini

import com.mingeek.opiczh.core.common.AppError
import com.mingeek.opiczh.core.common.AppResult
import com.mingeek.opiczh.core.common.AppTracer
import com.mingeek.opiczh.core.common.WavCodec
import com.mingeek.opiczh.core.common.errorOrNull
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
    private val quotaGuard: ModelQuotaGuard,
    private val pacer: RequestPacer,
    private val json: Json,
    private val tracer: AppTracer,
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
        // 한도 초과로 쿨다운 중이면 호출 없이 즉시 실패 → ChineseSpeaker가 곧장 시스템 TTS로 폴백
        val cooldownMs = quotaGuard.remainingCooldownMs(settings.ttsModelId)
        if (cooldownMs > 0) {
            return AppResult.failure(
                AppError.RateLimited(retryAfterSec = (cooldownMs / 1_000L).coerceAtLeast(1L).toInt()),
            )
        }
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
        return tracer.trace("tts_synthesize", "chars" to text.length.toString()) {
            // 재생 직전 호출에서 긴 429 대기는 곧 "문항이 안 나오는 침묵"이다 —
            // 아주 짧은 힌트만 기다리고, 아니면 즉시 실패해 시스템 TTS로 폴백시킨다.
            val result = retryWithBackoff(maxRateLimitWaitMs = MAX_RATE_LIMIT_WAIT_MS) {
                call(settings.ttsModelId, body)
            }
            val limited = result.errorOrNull() as? AppError.RateLimited
            when {
                limited != null -> quotaGuard.markLimited(settings.ttsModelId, limited.retryAfterSec)
                result is AppResult.Success -> quotaGuard.markRecovered(settings.ttsModelId)
            }
            result
        }
    }

    private suspend fun call(model: String, body: GenerateContentRequest): AppResult<ByteArray> {
        pacer.awaitSlot(model)
        return try {
            val response = api.generateContent(model, body)
            if (response.isSuccessful) {
                extractWav(response.body())
            } else {
                AppResult.failure(
                    GeminiErrorMapper.fromResponse(
                        response.code(),
                        response.errorBody()?.string(),
                        json,
                        retryAfterHeaderSec = response.headers()["Retry-After"]?.toIntOrNull(),
                    ),
                )
            }
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            AppResult.failure(GeminiErrorMapper.fromThrowable(t))
        }
    }

    private companion object {
        /** TTS 429 인플레이스 대기 상한 — 이보다 길면 즉시 시스템 TTS 폴백이 낫다 */
        const val MAX_RATE_LIMIT_WAIT_MS = 5_000L
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
