package com.mingeek.opiczh.core.ai.gemini

import com.mingeek.opiczh.core.common.AppError
import com.mingeek.opiczh.core.common.AppResult
import com.mingeek.opiczh.core.common.map
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

data class LlmModelInfo(
    val id: String,
    val displayName: String,
    val description: String? = null,
)

/** 사용 가능한 Gemini 모델 목록 조회 + API 키 검증 */
@Singleton
class GeminiModelCatalog @Inject constructor(
    private val api: GeminiApi,
    private val json: Json,
) {

    /**
     * generateContent를 지원하는 모델 목록.
     * @param apiKeyOverride 저장 전 후보 키를 검증할 때만 전달.
     */
    suspend fun listTextModels(apiKeyOverride: String? = null): AppResult<List<LlmModelInfo>> =
        try {
            val response = api.listModels(apiKeyOverride = apiKeyOverride?.takeIf { it.isNotBlank() })
            if (response.isSuccessful) {
                val models = response.body()?.models.orEmpty()
                    .filter { it.supportedGenerationMethods.orEmpty().contains("generateContent") }
                    .map { dto ->
                        LlmModelInfo(
                            id = dto.name.removePrefix("models/"),
                            displayName = dto.displayName ?: dto.name.removePrefix("models/"),
                            description = dto.description,
                        )
                    }
                AppResult.success(models)
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

    /** 후보 키로 models.list 핑. 성공 시 사용 가능 모델 수 반환. */
    suspend fun validateApiKey(candidateKey: String): AppResult<Int> {
        if (candidateKey.isBlank()) {
            return AppResult.failure(AppError.ApiKeyInvalid("키가 비어 있습니다"))
        }
        return listTextModels(apiKeyOverride = candidateKey).map { it.size }
    }
}
