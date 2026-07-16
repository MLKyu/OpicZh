package com.mingeek.opiczh.core.ai

import com.mingeek.opiczh.core.model.OnDeviceEnginePriority
import org.junit.Assert.assertEquals
import org.junit.Test

class LlmRoutingTest {

    @Test
    fun `다운로드 모델 우선 - 둘 다 준비되면 다운로드 모델부터 Nano 순서`() {
        assertEquals(
            listOf(LlmEngineId.ON_DEVICE, LlmEngineId.NANO),
            LlmRouting.onDeviceOrder(
                OnDeviceEnginePriority.DOWNLOADED_FIRST,
                downloadedReady = true,
                nanoReady = true,
            ),
        )
    }

    @Test
    fun `다운로드 모델이 없으면 기본값에서도 Nano가 자동으로 이어받는다`() {
        assertEquals(
            listOf(LlmEngineId.NANO),
            LlmRouting.onDeviceOrder(
                OnDeviceEnginePriority.DOWNLOADED_FIRST,
                downloadedReady = false,
                nanoReady = true,
            ),
        )
    }

    @Test
    fun `Nano 우선으로 바꾸면 Nano부터 다운로드 모델 순서`() {
        assertEquals(
            listOf(LlmEngineId.NANO, LlmEngineId.ON_DEVICE),
            LlmRouting.onDeviceOrder(
                OnDeviceEnginePriority.NANO_FIRST,
                downloadedReady = true,
                nanoReady = true,
            ),
        )
    }

    @Test
    fun `Nano 우선이어도 Nano 미지원이면 다운로드 모델을 쓴다`() {
        assertEquals(
            listOf(LlmEngineId.ON_DEVICE),
            LlmRouting.onDeviceOrder(
                OnDeviceEnginePriority.NANO_FIRST,
                downloadedReady = true,
                nanoReady = false,
            ),
        )
    }

    @Test
    fun `아무것도 준비 안 되면 빈 목록`() {
        assertEquals(
            emptyList<LlmEngineId>(),
            LlmRouting.onDeviceOrder(
                OnDeviceEnginePriority.DOWNLOADED_FIRST,
                downloadedReady = false,
                nanoReady = false,
            ),
        )
    }
}
