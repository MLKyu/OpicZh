package com.mingeek.opiczh.core.ai.gemini

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelQuotaGuardTest {

    @Test
    fun `첫 429는 짧은 힌트를 신뢰하되 최소 쿨다운을 지킨다`() {
        assertEquals(15, ModelQuotaGuard.cooldownSec(strike = 1, retryAfterSec = 3))
        assertEquals(45, ModelQuotaGuard.cooldownSec(strike = 1, retryAfterSec = 40))
        // 힌트 없음 → 다음 분 창까지 보수적으로
        assertEquals(75, ModelQuotaGuard.cooldownSec(strike = 1, retryAfterSec = null))
    }

    @Test
    fun `연속 429는 거짓 짧은 힌트를 무시하고 지수로 쿨다운을 늘린다`() {
        // RPD 소진인데 retryDelay는 3초라고 하는 경우 — 하한이 배로 커진다
        assertEquals(60, ModelQuotaGuard.cooldownSec(strike = 2, retryAfterSec = 3))
        assertEquals(120, ModelQuotaGuard.cooldownSec(strike = 3, retryAfterSec = 3))
        assertEquals(240, ModelQuotaGuard.cooldownSec(strike = 4, retryAfterSec = 3))
        assertEquals(480, ModelQuotaGuard.cooldownSec(strike = 5, retryAfterSec = null))
    }

    @Test
    fun `에스컬레이션은 30분에서 멈춘다`() {
        assertEquals(1_800, ModelQuotaGuard.cooldownSec(strike = 8, retryAfterSec = 3))
        assertEquals(1_800, ModelQuotaGuard.cooldownSec(strike = 50, retryAfterSec = 3))
    }

    @Test
    fun `정직하게 긴 힌트는 스트라이크와 무관하게 그대로 존중한다`() {
        assertEquals(20_005, ModelQuotaGuard.cooldownSec(strike = 1, retryAfterSec = 20_000))
        assertEquals(20_005, ModelQuotaGuard.cooldownSec(strike = 5, retryAfterSec = 20_000))
    }
}
