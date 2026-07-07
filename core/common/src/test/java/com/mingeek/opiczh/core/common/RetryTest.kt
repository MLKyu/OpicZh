package com.mingeek.opiczh.core.common

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
}
