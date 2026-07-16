package com.mingeek.opiczh.core.ai

import com.mingeek.opiczh.core.model.OnDeviceEnginePriority

/** 온디바이스 엔진 시도 순서를 정하는 순수 로직 (LlmRouter가 사용) */
internal object LlmRouting {

    /**
     * 우선순위에 따라 지금 사용할 수 있는 온디바이스 엔진을 순서대로 반환한다.
     * 준비 안 된 엔진은 제외 — 기본값(다운로드 모델 우선)에서도 받아둔 모델이 없으면
     * 내장 Nano가 자동으로 이어받고, 반대로 Nano 미지원 기기에선 다운로드 모델만 쓴다.
     */
    fun onDeviceOrder(
        priority: OnDeviceEnginePriority,
        downloadedReady: Boolean,
        nanoReady: Boolean,
    ): List<LlmEngineId> {
        val candidates = when (priority) {
            OnDeviceEnginePriority.DOWNLOADED_FIRST -> listOf(
                LlmEngineId.ON_DEVICE to downloadedReady,
                LlmEngineId.NANO to nanoReady,
            )
            OnDeviceEnginePriority.NANO_FIRST -> listOf(
                LlmEngineId.NANO to nanoReady,
                LlmEngineId.ON_DEVICE to downloadedReady,
            )
        }
        return candidates.filter { it.second }.map { it.first }
    }
}
