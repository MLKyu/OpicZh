package com.mingeek.opiczh.core.ai.stt

import com.mingeek.opiczh.core.common.AppError

/**
 * 클라우드 음성 처리 실패 → 온디바이스 STT 우회 여부 결정.
 *
 * 우회 대상은 "클라우드를 지금 못 쓰는" 오류뿐이다:
 * - [AppError.RateLimited]: 무료 한도 소진 (모델 체인 전부 쿨다운)
 * - [AppError.ApiKeyMissing]: 키 미등록
 * - [AppError.OnDeviceUnavailable]: ON_DEVICE_ONLY 정책의 오디오 선판정 거절
 * - [AppError.Network]: 오프라인 (비행기 모드 연습)
 * 내용 오류(파싱·BadRequest·오디오 손상 등)는 우회해도 같은 이유로 실패하므로 제외.
 */
object SttBypassPolicy {

    fun shouldBypass(error: AppError, sttReady: Boolean): Boolean = sttReady && when (error) {
        is AppError.RateLimited,
        AppError.ApiKeyMissing,
        is AppError.OnDeviceUnavailable,
        is AppError.Network,
        -> true

        else -> false
    }
}
