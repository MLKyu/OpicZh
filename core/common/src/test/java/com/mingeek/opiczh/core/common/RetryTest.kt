package com.mingeek.opiczh.core.common

import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryTest {

    @Test
    fun `returns immediately on success`() = runTest {
        var calls = 0
        val result = retryWithBackoff(times = 3) {
            calls++
            AppResult.success("ok")
        }
        assertEquals("ok", result.getOrNull())
        assertEquals(1, calls)
    }

    @Test
    fun `does not retry non-retryable errors`() = runTest {
        var calls = 0
        val result = retryWithBackoff(times = 3) {
            calls++
            AppResult.failure(AppError.ApiKeyInvalid("bad"))
        }
        assertTrue(result.errorOrNull() is AppError.ApiKeyInvalid)
        assertEquals(1, calls)
    }

    @Test
    fun `retries retryable errors up to limit`() = runTest {
        var calls = 0
        val result = retryWithBackoff(times = 3, initialDelayMs = 1) {
            calls++
            AppResult.failure(AppError.Network("down"))
        }
        assertTrue(result.errorOrNull() is AppError.Network)
        assertEquals(3, calls)
    }

    @Test
    fun `recovers when a retry succeeds`() = runTest {
        var calls = 0
        val result = retryWithBackoff(times = 3, initialDelayMs = 1) {
            calls++
            if (calls < 3) AppResult.failure(AppError.Server(503, "unavailable"))
            else AppResult.success(42)
        }
        assertEquals(42, result.getOrNull())
        assertEquals(3, calls)
    }

    @Test
    fun `waits the server-hinted delay on 429`() = runTest {
        var calls = 0
        val startMs = currentTime
        val result = retryWithBackoff(times = 2) {
            calls++
            if (calls == 1) AppResult.failure(AppError.RateLimited(retryAfterSec = 21))
            else AppResult.success("ok")
        }
        assertEquals("ok", result.getOrNull())
        assertEquals(2, calls)
        // 힌트 21초 + 여유 1초 이상 기다렸어야 한다 (기존 8초 캡에 잘리면 안 됨)
        assertTrue(currentTime - startMs >= 21_000)
    }

    @Test
    fun `gives up immediately when 429 wait exceeds cap`() = runTest {
        var calls = 0
        val result = retryWithBackoff(times = 3) {
            calls++
            AppResult.failure(AppError.RateLimited(retryAfterSec = 3600))
        }
        val error = result.errorOrNull() as AppError.RateLimited
        assertEquals(3600, error.retryAfterSec)
        assertEquals(1, calls)
    }

    @Test
    fun `uses default wait for 429 without hint`() = runTest {
        var calls = 0
        val startMs = currentTime
        retryWithBackoff(times = 2, defaultRateLimitWaitMs = 5_000) {
            calls++
            AppResult.failure(AppError.RateLimited())
        }
        assertEquals(2, calls)
        assertTrue(currentTime - startMs >= 5_000)
    }

    @Test
    fun `maxRateLimitWaitMs=0 disables in-place 429 retries entirely`() = runTest {
        var calls = 0
        val result = retryWithBackoff(times = 3, maxRateLimitWaitMs = 0) {
            calls++
            AppResult.failure(AppError.RateLimited(retryAfterSec = 3))
        }
        // 짧은 힌트라도 기다리지 않고 즉시 반환 — 상위 모델 체인이 갈아탄다
        assertEquals(1, calls)
        assertEquals(0, currentTime)
        assertEquals(3, (result.errorOrNull() as AppError.RateLimited).retryAfterSec)
    }

    @Test
    fun `unhinted 429 respects the wait cap too`() = runTest {
        var calls = 0
        retryWithBackoff(times = 3, defaultRateLimitWaitMs = 12_000, maxRateLimitWaitMs = 5_000) {
            calls++
            AppResult.failure(AppError.RateLimited())
        }
        assertEquals(1, calls)
        assertEquals(0, currentTime)
    }

    @Test
    fun `maxRateLimitWaitMs=0 still retries network errors`() = runTest {
        var calls = 0
        val result = retryWithBackoff(times = 3, initialDelayMs = 1, maxRateLimitWaitMs = 0) {
            calls++
            if (calls < 2) AppResult.failure(AppError.Network("blip"))
            else AppResult.success("ok")
        }
        assertEquals("ok", result.getOrNull())
        assertEquals(2, calls)
    }
}
