package com.mingeek.opiczh.core.common

import kotlinx.coroutines.delay

/**
 * 일시적 오류(네트워크/429/5xx)에 대해 지수 백오프로 재시도한다.
 * 재시도 불가 오류는 즉시 반환한다.
 */
suspend fun <T> retryWithBackoff(
    times: Int = 3,
    initialDelayMs: Long = 800,
    maxDelayMs: Long = 8_000,
    factor: Double = 2.0,
    block: suspend () -> AppResult<T>,
): AppResult<T> {
    var currentDelay = initialDelayMs
    var lastResult: AppResult<T> = block()
    var attempt = 1
    while (attempt < times) {
        val error = lastResult.errorOrNull() ?: return lastResult
        if (!error.isRetryable) return lastResult
        val waitMs = (error as? AppError.RateLimited)?.retryAfterSec?.times(1000L) ?: currentDelay
        delay(waitMs.coerceAtMost(maxDelayMs))
        currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelayMs)
        lastResult = block()
        attempt++
    }
    return lastResult
}
