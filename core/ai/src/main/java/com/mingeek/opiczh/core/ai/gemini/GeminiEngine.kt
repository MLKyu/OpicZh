package com.mingeek.opiczh.core.ai.gemini

import com.mingeek.opiczh.core.ai.LlmEngine
import com.mingeek.opiczh.core.ai.LlmEngineId
import com.mingeek.opiczh.core.ai.LlmPart
import com.mingeek.opiczh.core.ai.LlmReply
import com.mingeek.opiczh.core.ai.LlmRequest
import com.mingeek.opiczh.core.ai.UsageTracker
import com.mingeek.opiczh.core.common.AppError
import com.mingeek.opiczh.core.common.AppResult
import com.mingeek.opiczh.core.common.retryWithBackoff
import com.mingeek.opiczh.core.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/** Gemini Developer API(BYOK) 기반 클라우드 엔진 */
@Singleton
class GeminiEngine @Inject constructor(
    private val api: GeminiApi,
    private val apiKeyHolder: ApiKeyHolder,
    private val settingsRepository: SettingsRepository,
    private val json: Json,
    private val usageTracker: UsageTracker,
) : LlmEngine {

    override val id: LlmEngineId = LlmEngineId.GEMINI

    override suspend fun isReady(): Boolean = !apiKeyHolder.current.isNullOrBlank()

    override suspend fun generate(request: LlmRequest): AppResult<LlmReply> {
        if (!isReady()) return AppResult.failure(AppError.ApiKeyMissing)
        val model = request.modelOverride
            ?: settingsRepository.settings.first().textModelId
        val body = buildRequestBody(request)
        return retryWithBackoff { call(model, body) }
    }

    private suspend fun call(model: String, body: GenerateContentRequest): AppResult<LlmReply> =
        try {
            val response = api.generateContent(model, body)
            if (response.isSuccessful) {
                extractReply(response.body())
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

    private fun extractReply(body: GenerateContentResponse?): AppResult<LlmReply> {
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
