package com.mingeek.opiczh.core.ai.ondevice

import kotlinx.serialization.Serializable

/** 다운로드 가능한 온디바이스 모델 사양 (추천 결과 영속화를 위해 직렬화 가능) */
@Serializable
data class OnDeviceModelSpec(
    val id: String,
    val displayName: String,
    val description: String,
    val url: String,
    val fileName: String,
    val approxSizeMb: Int,
    /** HuggingFace 게이트 모델 여부 (동의 + 토큰 필요) */
    val requiresHfToken: Boolean,
)

/** 저장되는 추천 결과 */
@Serializable
data class RecommendationRecord(
    val spec: OnDeviceModelSpec,
    val reasonKo: String,
    /** "AI 판정" 또는 "규칙 기반" */
    val decidedBy: String,
    val checkedAtEpochMs: Long,
    val candidatesConsidered: Int,
)

/**
 * 검증된 프리셋 (HuggingFace LiteRT Community).
 * S26 Ultra(12GB+ RAM) 기준 전부 여유 있게 구동 가능.
 */
object OnDeviceModels {

    val QWEN3_4B = OnDeviceModelSpec(
        id = "qwen3-4b-instruct",
        displayName = "Qwen3 4B Instruct (권장)",
        description = "중국어 최강 오픈 모델. 오프라인 드릴 피드백·회화용. 토큰 없이 다운로드 가능.",
        url = "https://huggingface.co/litert-community/Qwen3-4B-Instruct-2507/resolve/main/qwen3_4b_instruct_2507_mixed_int4.litertlm",
        fileName = "qwen3-4b-instruct-int4.litertlm",
        approxSizeMb = 2400,
        requiresHfToken = false,
    )

    val GEMMA3_1B = OnDeviceModelSpec(
        id = "gemma3-1b",
        displayName = "Gemma 3 1B",
        description = "가볍고 빠름(~550MB). 간단한 연습용. HuggingFace 토큰 필요.",
        url = "https://huggingface.co/litert-community/Gemma3-1B-IT/resolve/main/gemma3-1b-it-int4.litertlm",
        fileName = "gemma3-1b-it-int4.litertlm",
        approxSizeMb = 550,
        requiresHfToken = true,
    )

    val GEMMA3N_E2B = OnDeviceModelSpec(
        id = "gemma3n-e2b",
        displayName = "Gemma 3n E2B",
        description = "구글 최신 온디바이스 모델(~3GB). HuggingFace 토큰 필요.",
        url = "https://huggingface.co/google/gemma-3n-E2B-it-litert-lm/resolve/main/gemma-3n-E2B-it-int4.litertlm",
        fileName = "gemma-3n-e2b-int4.litertlm",
        approxSizeMb = 3100,
        requiresHfToken = true,
    )

    /** 우선순위 순서 — 설치된 것 중 앞선 모델이 사용된다 */
    val ALL = listOf(QWEN3_4B, GEMMA3N_E2B, GEMMA3_1B)

    fun byId(id: String): OnDeviceModelSpec? = ALL.firstOrNull { it.id == id }
}
