package com.mingeek.opiczh.core.ai

import com.mingeek.opiczh.core.ai.gemini.GeminiEngine
import com.mingeek.opiczh.core.common.AppError
import com.mingeek.opiczh.core.common.AppResult
import com.mingeek.opiczh.core.common.errorOrNull
import com.mingeek.opiczh.core.common.flatMap
import com.mingeek.opiczh.core.data.settings.SettingsRepository
import com.mingeek.opiczh.core.model.OnDeviceEnginePriority
import com.mingeek.opiczh.core.model.RoutingPolicy
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 라우팅 정책과 엔진 준비 상태에 따라 요청을 보낼 엔진을 고른다.
 * - CLOUD_ONLY: 클라우드(Gemini) 강제
 * - ON_DEVICE_ONLY: 온디바이스 강제 — 우선순위 설정에 따라 다운로드 모델(LiteRT)/내장 Nano 순서 결정
 * - AUTO: 클라우드 우선, 불가하면 온디바이스 폴백
 */
@Singleton
class LlmRouter @Inject constructor(
    private val gemini: GeminiEngine,
    private val onDevice: OnDeviceLlmEngine,
    private val nano: NanoLlmEngine,
    private val settingsRepository: SettingsRepository,
) {

    suspend fun engineFor(task: AiTask): AppResult<LlmEngine> {
        val settings = settingsRepository.settings.first()
        val cloudReady = gemini.isReady()
        return when (settings.routingPolicy) {
            RoutingPolicy.CLOUD_ONLY ->
                if (cloudReady) AppResult.success(gemini)
                else AppResult.failure(AppError.ApiKeyMissing)

            RoutingPolicy.ON_DEVICE_ONLY ->
                readyOnDeviceEngines(settings.onDeviceEnginePriority).firstOrNull()
                    ?.let { AppResult.success(it) }
                    ?: AppResult.failure(AppError.OnDeviceUnavailable())

            RoutingPolicy.AUTO -> when {
                cloudReady -> AppResult.success(gemini)
                else -> readyOnDeviceEngines(settings.onDeviceEnginePriority).firstOrNull()
                    ?.let { AppResult.success(it) }
                    ?: AppResult.failure(AppError.ApiKeyMissing)
            }
        }
    }

    /**
     * 엔진 선택 + 생성까지 한 번에.
     * AUTO 정책에서 클라우드가 모든 모델의 한도를 소진했을 때, 오디오가 없는 요청은
     * 온디바이스로 이어서 처리한다 (회화·드릴은 한도와 무관하게 계속 동작).
     * 온디바이스는 우선순위 순서로 시도하고(다운로드 모델 ↔ 내장 Nano), 전부 실패하면
     * 원래의 한도 초과 결과를 그대로 반환한다 — retryAfterSec 힌트 기반 대기·2차 패스가
     * 이 정보를 사용하기 때문.
     */
    suspend fun generate(task: AiTask, request: LlmRequest): AppResult<LlmReply> {
        val result = engineFor(task).flatMap { engine -> engine.generate(request) }
        val rateLimited = result.errorOrNull() is AppError.RateLimited
        if (!rateLimited) return result

        val settings = settingsRepository.settings.first()
        val textOnly = request.parts.none { it is LlmPart.Audio }
        if (settings.routingPolicy != RoutingPolicy.AUTO || !textOnly) return result

        for (engine in readyOnDeviceEngines(settings.onDeviceEnginePriority)) {
            val fallback = engine.generate(request)
            if (fallback is AppResult.Success) return fallback
        }
        return result
    }

    /** 우선순위에 따라 지금 사용할 수 있는 온디바이스 엔진 목록 (준비 안 된 엔진 제외) */
    private suspend fun readyOnDeviceEngines(priority: OnDeviceEnginePriority): List<LlmEngine> =
        LlmRouting.onDeviceOrder(
            priority = priority,
            downloadedReady = onDevice.isReady(),
            nanoReady = nano.isReady(),
        ).map { id -> if (id == LlmEngineId.NANO) nano else onDevice }
}
