package com.mingeek.opiczh.core.model

import kotlinx.serialization.Serializable

/** AI 엔진 라우팅 정책 */
@Serializable
enum class RoutingPolicy(val ko: String) {
    /** 클라우드 가능하면 클라우드, 아니면 온디바이스 */
    AUTO("자동 (권장)"),
    CLOUD_ONLY("클라우드만 (Gemini)"),
    ON_DEVICE_ONLY("온디바이스만"),
}

/**
 * 기본 모델 ID. 모델명은 언제든 바뀔 수 있으므로 하드코딩하지 않고
 * models.list 동적 조회로 관리하며, 여기는 조회 전(첫 실행·오프라인) 기본값만 둔다.
 * 텍스트 모델은 사용자가 고르지 않는다 — 키로 쓸 수 있는 모델을 자동 서열화해
 * 좋은 모델부터 쓰고, 한도가 차면 다음 모델로 자동 전환한다 (ModelChain).
 */
object DefaultModels {
    const val TEXT: String = "gemini-3.5-flash"
    const val TTS: String = "gemini-3.1-flash-tts-preview"
    const val TTS_VOICE: String = "Kore"
}

/** 앱 전역 설정 스냅샷 */
data class AppSettings(
    val hasApiKey: Boolean = false,
    val targetGrade: TargetGrade = TargetGrade.DEFAULT,
    val ttsModelId: String = DefaultModels.TTS,
    val ttsVoice: String = DefaultModels.TTS_VOICE,
    val routingPolicy: RoutingPolicy = RoutingPolicy.AUTO,
)
