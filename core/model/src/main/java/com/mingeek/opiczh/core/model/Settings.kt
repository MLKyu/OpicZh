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
 * 설정값 + models.list 동적 조회로 관리하며, 여기는 초기 기본값만 둔다.
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
    val textModelId: String = DefaultModels.TEXT,
    val ttsModelId: String = DefaultModels.TTS,
    val ttsVoice: String = DefaultModels.TTS_VOICE,
    val routingPolicy: RoutingPolicy = RoutingPolicy.AUTO,
)
