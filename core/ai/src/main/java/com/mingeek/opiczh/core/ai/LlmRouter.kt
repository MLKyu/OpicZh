package com.mingeek.opiczh.core.ai

import com.mingeek.opiczh.core.ai.gemini.GeminiEngine
import com.mingeek.opiczh.core.common.AppError
import com.mingeek.opiczh.core.common.AppResult
import com.mingeek.opiczh.core.common.errorOrNull
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

    /**
     * 엔진 선택 + 생성까지 한 번에.
     * AUTO 정책에서 클라우드가 모든 모델의 한도를 소진했을 때, 오디오가 없는 요청은
     * 온디바이스로 이어서 처리한다 (회화·드릴은 한도와 무관하게 계속 동작).
     */
    suspend fun generate(task: AiTask, request: LlmRequest): AppResult<LlmReply> {
        val result = engineFor(task).flatMap { engine -> engine.generate(request) }
        val rateLimited = result.errorOrNull() is AppError.RateLimited
        if (!rateLimited) return result

        val policy = settingsRepository.settings.first().routingPolicy
        val textOnly = request.parts.none { it is LlmPart.Audio }
        if (policy == RoutingPolicy.AUTO && textOnly && onDevice.isReady()) {
            return onDevice.generate(request)
        }
        return result
    }
}
