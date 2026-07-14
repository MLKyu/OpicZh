package com.mingeek.opiczh.core.common

import kotlinx.coroutines.delay

/**
 * 일시적 오류(네트워크/429/5xx)에 대해 지수 백오프로 재시도한다.
 * 재시도 불가 오류는 즉시 반환한다.
 *
 * 429는 서버가 알려준 대기시간(retryAfterSec)을 그대로 기다린다 — 분당 한도(RPM)는
 * 다음 분 창이 열리면 풀리기 때문. 단 [maxRateLimitWaitMs]를 넘는 대기(일일 한도 소진 등)는
 * 기다려도 소용없으므로 즉시 반환해 상위(모델 체인)가 다른 모델로 전환하게 한다.
 * 즉시 갈아탈 대안이 있는 호출부(모델 체인)는 [maxRateLimitWaitMs]=0으로 429 대기를 끈다.
 */
suspend fun <T> retryWithBackoff(
    times: Int = 3,
    initialDelayMs: Long = 800,
    maxDelayMs: Long = 8_000,
    factor: Double = 2.0,
    /** 429에서 서버 대기시간이 없을 때 기본 대기 */
    defaultRateLimitWaitMs: Long = 12_000,
    /** 이보다 긴 429 대기는 재시도 포기 (일일 한도로 간주). 힌트 없는 429에도 적용된다 */
    maxRateLimitWaitMs: Long = 65_000,
    block: suspend () -> AppResult<T>,
): AppResult<T> {
    var currentDelay = initialDelayMs
    var lastResult: AppResult<T> = block()
    var attempt = 1
    while (attempt < times) {
        val error = lastResult.errorOrNull() ?: return lastResult
        if (!error.isRetryable) return lastResult
        val waitMs = if (error is AppError.RateLimited) {
            val hinted = error.retryAfterSec?.times(1000L)?.plus(RATE_LIMIT_MARGIN_MS)
                ?: defaultRateLimitWaitMs
            if (hinted > maxRateLimitWaitMs) return lastResult
            hinted
        } else {
            currentDelay
        }
        delay(waitMs)
        currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelayMs)
        lastResult = block()
        attempt++
    }
    return lastResult
}

/** 서버가 알려준 시각 직후 재시도가 다시 429로 튀는 것을 막는 여유분 */
private const val RATE_LIMIT_MARGIN_MS = 1_000L
