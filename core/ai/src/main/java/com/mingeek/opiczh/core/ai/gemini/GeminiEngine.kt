package com.mingeek.opiczh.core.ai.gemini

import com.mingeek.opiczh.core.ai.LlmEngine
import com.mingeek.opiczh.core.ai.LlmEngineId
import com.mingeek.opiczh.core.ai.LlmPart
import com.mingeek.opiczh.core.ai.LlmReply
import com.mingeek.opiczh.core.ai.LlmRequest
import com.mingeek.opiczh.core.ai.UsageTracker
import com.mingeek.opiczh.core.common.AppError
import com.mingeek.opiczh.core.common.AppResult
import com.mingeek.opiczh.core.common.errorOrNull
import com.mingeek.opiczh.core.common.retryWithBackoff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Gemini Developer API(BYOK) 기반 클라우드 엔진.
 *
 * 모델은 사용자가 고르지 않는다 — [ModelChainProvider]가 서열화한 체인을 좋은 모델부터
 * 시도하고, 한도 초과(429)한 모델은 [ModelQuotaGuard]에 쿨다운을 기록한 뒤 다음 모델로
 * 자동 전환한다. 각 호출은 [RequestPacer]로 분당 한도 안쪽에서 간격을 두고 나간다.
 */
@Singleton
class GeminiEngine @Inject constructor(
    private val api: GeminiApi,
    private val apiKeyHolder: ApiKeyHolder,
    private val chainProvider: ModelChainProvider,
    private val quotaGuard: ModelQuotaGuard,
    private val pacer: RequestPacer,
    private val json: Json,
    private val usageTracker: UsageTracker,
) : LlmEngine {

    override val id: LlmEngineId = LlmEngineId.GEMINI

    override suspend fun isReady(): Boolean = !apiKeyHolder.current.isNullOrBlank()

    override suspend fun generate(request: LlmRequest): AppResult<LlmReply> {
        if (!isReady()) return AppResult.failure(AppError.ApiKeyMissing)
        // MB급 오디오 base64 인코딩이 호출 스레드(대개 Main)를 막지 않게 한다
        val body = withContext(Dispatchers.Default) { buildRequestBody(request) }

        request.modelOverride?.let { forced ->
            return retryWithBackoff { pacedCall(forced, body) }
        }

        val chain = chainProvider.chain()
        var lastRateLimit: AppError.RateLimited? = null
        var lastServerError: AppError.Server? = null
        for (model in chain) {
            if (quotaGuard.remainingCooldownMs(model) > 0) continue
            // 429는 이 자리에서 기다리지 않는다(maxRateLimitWaitMs=0) — 쿨다운 기록 후 즉시
            // 다음 모델로 넘어간다. 여기서 retryDelay를 기다리면 채점처럼 호출이 많은 흐름에서
            // 대기가 모델 수×문항 수로 곱해져 화면이 몇십 분씩 멈춘 것처럼 보인다.
            val result = retryWithBackoff(maxRateLimitWaitMs = 0) { pacedCall(model, body) }
            when (val error = result.errorOrNull()) {
                null -> {
                    quotaGuard.markRecovered(model)
                    return result
                }
                is AppError.RateLimited -> {
                    quotaGuard.markLimited(model, error.retryAfterSec)
                    lastRateLimit = error // 다음 모델로 자동 전환
                }
                is AppError.Server -> lastServerError = error // 과부하(503 등)는 모델별 문제 — 다음 모델 시도
                else -> return result // 키/차단/네트워크 오류는 모델을 바꿔도 같다
            }
        }
        // 한도 초과가 하나도 없었다면(전부 서버 오류) 그 오류를 그대로 알린다
        if (lastRateLimit == null && lastServerError != null) {
            return AppResult.failure(lastServerError)
        }
        // 체인 전부 한도 초과: 가장 빨리 풀리는 시각을 알려준다
        return AppResult.failure(
            AppError.RateLimited(
                retryAfterSec = quotaGuard.soonestAvailableSec(chain)
                    ?: lastRateLimit?.retryAfterSec,
            ),
        )
    }

    private suspend fun pacedCall(
        model: String,
        body: GenerateContentRequest,
    ): AppResult<LlmReply> {
        pacer.awaitSlot(model)
        return try {
            val response = api.generateContent(model, body)
            if (response.isSuccessful) {
                extractReply(response.body(), model)
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

    private fun extractReply(body: GenerateContentResponse?, model: String): AppResult<LlmReply> {
        if (body == null) return AppResult.failure(AppError.Parsing("빈 응답"))
        body.promptFeedback?.blockReason?.let { reason ->
            return AppResult.failure(AppError.BadRequest("요청이 차단되었습니다: $reason"))
        }
        val candidate = body.candidates?.firstOrNull()
            ?: return AppResult.failure(AppError.Parsing("후보 응답 없음"))
        val text = candidate.content?.parts.orEmpty()
            .mapNotNull { it.text }
            .joinToString("")
        if (text.isBlank()) {
            return AppResult.failure(
                AppError.Parsing("응답 텍스트 없음 (finishReason=${candidate.finishReason})"),
            )
        }
        usageTracker.record(
            body.usageMetadata?.promptTokenCount,
            body.usageMetadata?.candidatesTokenCount,
        )
        return AppResult.success(
            LlmReply(
                text = text,
                promptTokens = body.usageMetadata?.promptTokenCount,
                outputTokens = body.usageMetadata?.candidatesTokenCount,
                modelId = model,
            ),
        )
    }

    private fun buildRequestBody(request: LlmRequest): GenerateContentRequest {
        val parts = request.parts.map { part ->
            when (part) {
                is LlmPart.Text -> Part(text = part.text)
                is LlmPart.Audio -> Part(
                    inlineData = Blob(
                        mimeType = part.mimeType,
                        data = Base64.getEncoder().encodeToString(part.bytes),
                    ),
                )
            }
        }
        val generationConfig = if (
            request.temperature != null ||
            request.maxOutputTokens != null ||
            request.responseJsonSchema != null
        ) {
            GenerationConfig(
                temperature = request.temperature,
                maxOutputTokens = request.maxOutputTokens,
                responseMimeType = request.responseJsonSchema?.let { "application/json" },
                responseSchema = request.responseJsonSchema,
            )
        } else {
            null
        }
        return GenerateContentRequest(
            contents = listOf(Content(parts = parts, role = "user")),
            systemInstruction = request.systemPrompt?.let {
                Content(parts = listOf(Part(text = it)))
            },
            generationConfig = generationConfig,
        )
    }
}
