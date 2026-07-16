package com.mingeek.opiczh.core.ai

import com.mingeek.opiczh.core.common.AppResult
import kotlinx.serialization.json.JsonObject

enum class LlmEngineId { GEMINI, ON_DEVICE, NANO }

/** 라우팅 판단에 쓰는 작업 종류. 정밀 채점은 클라우드, 드릴은 온디바이스 허용 등. */
enum class AiTask {
    GRADING,
    MODEL_ANSWER,
    DRILL_FEEDBACK,
    CHAT,
    QUESTION_GENERATION,
    TRANSLATION,
    TRANSCRIPTION,
}

sealed interface LlmPart {
    data class Text(val text: String) : LlmPart

    /** 녹음 답변 등 오디오 입력 (예: audio/mp4, audio/wav) */
    class Audio(val bytes: ByteArray, val mimeType: String) : LlmPart
}

data class LlmRequest(
    val parts: List<LlmPart>,
    val systemPrompt: String? = null,
    /** 지정 시 응답을 JSON으로 강제한다 (Gemini responseSchema) */
    val responseJsonSchema: JsonObject? = null,
    val temperature: Float? = null,
    val maxOutputTokens: Int? = null,
    /** 설정된 기본 모델 대신 사용할 모델 ID */
    val modelOverride: String? = null,
) {
    companion object {
        fun text(prompt: String, systemPrompt: String? = null): LlmRequest =
            LlmRequest(parts = listOf(LlmPart.Text(prompt)), systemPrompt = systemPrompt)
    }
}

data class LlmReply(
    val text: String,
    val promptTokens: Int? = null,
    val outputTokens: Int? = null,
    /** 실제 응답을 생성한 모델 (자동 체인 전환 추적용) */
    val modelId: String? = null,
)

/** 클라우드(Gemini)/온디바이스(LiteRT-LM) 공통 인터페이스 */
interface LlmEngine {
    val id: LlmEngineId

    /** 지금 이 엔진으로 요청을 보낼 수 있는 상태인가 (키 등록/모델 로드 여부) */
    suspend fun isReady(): Boolean

    suspend fun generate(request: LlmRequest): AppResult<LlmReply>
}
