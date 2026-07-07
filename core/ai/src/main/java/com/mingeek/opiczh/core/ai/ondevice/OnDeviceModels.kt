package com.mingeek.opiczh.core.ai.ondevice

import kotlinx.serialization.Serializable

/**
 * 다운로드 가능한 온디바이스 모델 사양 (추천 결과 영속화를 위해 직렬화 가능).
 * 이 앱은 HF 토큰을 쓰지 않으므로 여기 오르는 모델은 항상 토큰 없이 받을 수 있는 공개 모델이다.
 */
@Serializable
data class OnDeviceModelSpec(
    val id: String,
    val displayName: String,
    val description: String,
    val url: String,
    val fileName: String,
    val approxSizeMb: Int,
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
 *
 * 원칙: **전부 HF 토큰 없이 받을 수 있는 완전 공개 모델만** 둔다.
 * 온디바이스 LLM은 다운로드 후 기기에서 실행되므로 추론 비용은 0원이고,
 * 게이트(토큰·라이선스 동의 필요) 모델은 무료 공개 모델보다 나을 게 없어 넣지 않는다.
 * 파일명·용량·게이트 상태는 HuggingFace API로 확인한 실측값(2026-07).
 * S26 Ultra(12GB+ RAM) 기준 전부 여유 있게 구동 가능.
 */
object OnDeviceModels {

    val QWEN3_4B = OnDeviceModelSpec(
        id = "qwen3-4b-instruct",
        displayName = "Qwen3 4B Instruct (권장)",
        description = "중국어 성능 최상위 오픈 모델(알리바바). OPIc 회화·모범답안에 최적. 무료·토큰 불필요.",
        url = "https://huggingface.co/litert-community/Qwen3-4B-Instruct-2507/resolve/main/qwen3_4b_instruct_2507_mixed_int4.litertlm",
        fileName = "qwen3_4b_instruct_2507_mixed_int4.litertlm",
        approxSizeMb = 2536,
    )

    val GEMMA4_E2B = OnDeviceModelSpec(
        id = "gemma4-e2b",
        displayName = "Gemma 4 E2B (최신 · 한국어 설명 강점)",
        description = "구글 최신 온디바이스 모델. 한국어 문법 설명이 강해 교정 피드백에 좋음. 무료·토큰 불필요.",
        url = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        fileName = "gemma-4-E2B-it.litertlm",
        approxSizeMb = 2468,
    )

    val QWEN3_1_7B = OnDeviceModelSpec(
        id = "qwen3-1.7b",
        displayName = "Qwen3 1.7B (경량 · 빠름)",
        description = "가볍고 응답이 빠른 경량 모델(~2GB). 간단한 드릴·빠른 연습용. 무료·토큰 불필요.",
        url = "https://huggingface.co/litert-community/Qwen3-1.7B/resolve/main/Qwen3_1.7B.litertlm",
        fileName = "Qwen3_1.7B.litertlm",
        approxSizeMb = 1961,
    )

    /** 우선순위 순서 — 설치된 것 중 앞선 모델이 사용된다 */
    val ALL = listOf(QWEN3_4B, GEMMA4_E2B, QWEN3_1_7B)

    fun byId(id: String): OnDeviceModelSpec? = ALL.firstOrNull { it.id == id }
}
