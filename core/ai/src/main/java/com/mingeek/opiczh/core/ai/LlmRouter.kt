package com.mingeek.opiczh.core.ai

import com.mingeek.opiczh.core.ai.gemini.GeminiEngine
import com.mingeek.opiczh.core.common.AppError
import com.mingeek.opiczh.core.common.AppResult
import com.mingeek.opiczh.core.common.flatMap
import com.mingeek.opiczh.core.data.settings.SettingsRepository
import com.mingeek.opiczh.core.model.RoutingPolicy
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 라우팅 정책과 엔진 준비 상태에 따라 요청을 보낼 엔진을 고른다.
 * - CLOUD_ONLY / ON_DEVICE_ONLY: 해당 엔진 강제
 * - AUTO: 클라우드 우선, 불가하면 온디바이스 폴백
 */
@Singleton
class LlmRouter @Inject constructor(
    private val gemini: GeminiEngine,
    private val onDevice: OnDeviceLlmEngine,
    private val settingsRepository: SettingsRepository,
) {

    suspend fun engineFor(task: AiTask): AppResult<LlmEngine> {
        val policy = settingsRepository.settings.first().routingPolicy
        val cloudReady = gemini.isReady()
        val deviceReady = onDevice.isReady()
        return when (policy) {
            RoutingPolicy.CLOUD_ONLY ->
                if (cloudReady) AppResult.success(gemini)
                else AppResult.failure(AppError.ApiKeyMissing)

            RoutingPolicy.ON_DEVICE_ONLY ->
                if (deviceReady) AppResult.success(onDevice)
                else AppResult.failure(AppError.OnDeviceUnavailable())

            RoutingPolicy.AUTO -> when {
                cloudReady -> AppResult.success(gemini)
                deviceReady -> AppResult.success(onDevice)
                else -> AppResult.failure(AppError.ApiKeyMissing)
            }
        }
    }

    /** 엔진 선택 + 생성까지 한 번에 */
    suspend fun generate(task: AiTask, request: LlmRequest): AppResult<LlmReply> =
        engineFor(task).flatMap { engine -> engine.generate(request) }
}
