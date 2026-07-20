package com.mingeek.opiczh.core.ai

import com.mingeek.opiczh.core.ai.gemini.GeminiEngine
import com.mingeek.opiczh.core.common.AppError
import com.mingeek.opiczh.core.common.AppResult
import com.mingeek.opiczh.core.common.errorOrNull
import com.mingeek.opiczh.core.data.settings.SettingsRepository
import com.mingeek.opiczh.core.model.OnDeviceEnginePriority
import com.mingeek.opiczh.core.model.RoutingPolicy
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 라우팅 정책·요청 내용·엔진 준비 상태에 따라 요청을 보낼 엔진을 고른다.
 *
 * 음성 입력(채점·전사·발음 코치)은 클라우드 전용이다 — 온디바이스 엔진 2종(다운로드
 * LiteRT·내장 Nano)은 오디오를 지원하지 않으므로, 엔진에 보내 거절당하는 대신 여기서
 * 먼저 판정해 정확한 사유를 돌려준다.
 *
 * 텍스트 요청은 정책대로:
 * - CLOUD_ONLY: 클라우드(Gemini) 강제
 * - ON_DEVICE_ONLY: 온디바이스 강제 — 우선순위 순서(다운로드 모델 ↔ 내장 Nano)로
 *   차례로 시도해, 앞 엔진이 런타임에 실패해도 다음 엔진이 이어받는다
 * - AUTO: 클라우드 우선. 한도 초과(429)나 키 미등록이면 온디바이스가 같은 순서로 이어받는다
 */
@Singleton
class LlmRouter @Inject constructor(
    private val gemini: GeminiEngine,
    private val onDevice: OnDeviceLlmEngine,
    private val nano: NanoLlmEngine,
    private val settingsRepository: SettingsRepository,
) {

    suspend fun generate(task: AiTask, request: LlmRequest): AppResult<LlmReply> {
        val settings = settingsRepository.settings.first()
        val needsAudio = request.parts.any { it is LlmPart.Audio }

        if (needsAudio) {
            if (settings.routingPolicy == RoutingPolicy.ON_DEVICE_ONLY) {
                return AppResult.failure(
                    AppError.OnDeviceUnavailable(
                        "'온디바이스만' 모드에서는 ${task.ko} 기능을 쓸 수 없습니다. " +
                            "음성 입력은 클라우드(Gemini) 전용입니다 — " +
                            "설정에서 AI 엔진 사용 방식을 '자동'으로 바꿔 주세요.",
                    ),
                )
            }
            return if (gemini.isReady()) {
                gemini.generate(request)
            } else {
                AppResult.failure(AppError.ApiKeyMissing)
            }
        }

        return when (settings.routingPolicy) {
            RoutingPolicy.CLOUD_ONLY ->
                if (gemini.isReady()) gemini.generate(request)
                else AppResult.failure(AppError.ApiKeyMissing)

            RoutingPolicy.ON_DEVICE_ONLY ->
                generateOnDevice(settings.onDeviceEnginePriority, request)
                    ?: AppResult.failure(
                        AppError.OnDeviceUnavailable(
                            "사용할 수 있는 온디바이스 AI가 없습니다 — 다운로드한 모델이 없고 " +
                                "내장 Nano도 준비되지 않았습니다. 설정에서 모델을 다운로드하거나 " +
                                "내장 Nano를 준비해 주세요.",
                        ),
                    )

            RoutingPolicy.AUTO -> when {
                gemini.isReady() -> {
                    val result = gemini.generate(request)
                    if (result.errorOrNull() !is AppError.RateLimited) {
                        result
                    } else {
                        // 클라우드가 모든 모델의 한도를 소진 → 온디바이스가 이어받는다
                        // (회화·드릴·텍스트 채점은 한도와 무관하게 계속 동작).
                        // 온디바이스까지 실패하면 원래의 한도 초과 결과를 그대로 반환 —
                        // retryAfterSec 힌트 기반 대기·2차 패스가 이 정보를 사용하기 때문.
                        generateOnDevice(settings.onDeviceEnginePriority, request)
                            ?.takeIf { it is AppResult.Success }
                            ?: result
                    }
                }
                else -> generateOnDevice(settings.onDeviceEnginePriority, request)
                    ?: AppResult.failure(AppError.ApiKeyMissing)
            }
        }
    }

    /**
     * 우선순위 순서로 온디바이스 엔진을 차례로 시도한다. 앞 엔진이 실패하면(모델 로드
     * 실패·추론 오류 등) 다음 엔진이 이어받고, 전부 실패하면 마지막 실패를 반환한다.
     * 준비된 엔진이 하나도 없으면 null.
     */
    private suspend fun generateOnDevice(
        priority: OnDeviceEnginePriority,
        request: LlmRequest,
    ): AppResult<LlmReply>? {
        var last: AppResult<LlmReply>? = null
        for (engine in readyOnDeviceEngines(priority)) {
            last = engine.generate(request)
            if (last is AppResult.Success) return last
        }
        return last
    }

    /** 우선순위에 따라 지금 사용할 수 있는 온디바이스 엔진 목록 (준비 안 된 엔진 제외) */
    private suspend fun readyOnDeviceEngines(priority: OnDeviceEnginePriority): List<LlmEngine> =
        LlmRouting.onDeviceOrder(
            priority = priority,
            downloadedReady = onDevice.isReady(),
            nanoReady = nano.isReady(),
        ).map { id -> if (id == LlmEngineId.NANO) nano else onDevice }
}
