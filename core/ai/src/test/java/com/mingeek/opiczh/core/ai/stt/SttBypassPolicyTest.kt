package com.mingeek.opiczh.core.ai.stt

import com.mingeek.opiczh.core.common.AppError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SttBypassPolicyTest {

    private val bypassable = listOf(
        AppError.RateLimited(retryAfterSec = 120),
        AppError.RateLimited(retryAfterSec = null),
        AppError.ApiKeyMissing,
        AppError.OnDeviceUnavailable("온디바이스만 모드"),
        AppError.Network("오프라인"),
    )

    private val notBypassable = listOf(
        AppError.ApiKeyInvalid("bad key"),
        AppError.Parsing("json"),
        AppError.BadRequest("schema"),
        AppError.Audio("무음"),
        AppError.Server(500, "oops"),
        AppError.Unknown("?"),
    )

    @Test
    fun `클라우드 불가 오류는 STT 설치 시 우회`() {
        bypassable.forEach { error ->
            assertTrue("$error 는 우회 대상이어야 함", SttBypassPolicy.shouldBypass(error, sttReady = true))
        }
    }

    @Test
    fun `내용 오류는 우회하지 않는다`() {
        notBypassable.forEach { error ->
            assertFalse("$error 는 우회하면 안 됨", SttBypassPolicy.shouldBypass(error, sttReady = true))
        }
    }

    @Test
    fun `STT 미설치면 모든 오류에서 우회 없음`() {
        (bypassable + notBypassable).forEach { error ->
            assertFalse(SttBypassPolicy.shouldBypass(error, sttReady = false))
        }
    }
}
