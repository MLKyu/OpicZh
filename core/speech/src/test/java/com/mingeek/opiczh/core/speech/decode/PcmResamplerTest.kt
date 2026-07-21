package com.mingeek.opiczh.core.speech.decode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmResamplerTest {

    @Test
    fun `모노 PCM16은 값만 정규화된다`() {
        val out = PcmResampler.toMonoFloat(shortArrayOf(0, 16384, -16384, 32767), channels = 1)
        assertEquals(4, out.size)
        assertEquals(0f, out[0], 1e-6f)
        assertEquals(0.5f, out[1], 1e-4f)
        assertEquals(-0.5f, out[2], 1e-4f)
    }

    @Test
    fun `스테레오는 채널 평균으로 다운믹스`() {
        // 프레임1: (100, 300) → 200, 프레임2: (-200, 200) → 0
        val out = PcmResampler.toMonoFloat(shortArrayOf(100, 300, -200, 200), channels = 2)
        assertEquals(2, out.size)
        assertEquals(200 / 32768f, out[0], 1e-6f)
        assertEquals(0f, out[1], 1e-6f)
    }

    @Test
    fun `같은 샘플레이트는 그대로 반환`() {
        val input = floatArrayOf(0.1f, 0.2f, 0.3f)
        assertTrue(PcmResampler.resample(input, 16_000, 16_000) === input)
    }

    @Test
    fun `44100 to 16000 길이 비율`() {
        val input = FloatArray(44_100) { 0.5f }
        val out = PcmResampler.resample(input, 44_100, 16_000)
        assertEquals(16_000, out.size)
        out.forEach { assertEquals(0.5f, it, 1e-4f) }
    }

    @Test
    fun `업샘플 보간은 범위를 벗어나지 않는다`() {
        val input = floatArrayOf(-1f, 1f, -1f, 1f)
        val out = PcmResampler.resample(input, 8_000, 16_000)
        assertEquals(8, out.size)
        out.forEach { assertTrue(it in -1f..1f) }
    }

    @Test
    fun `마지막 샘플 경계에서 인덱스 초과 없음`() {
        val input = floatArrayOf(0f, 1f)
        val out = PcmResampler.resample(input, 48_000, 16_000)
        assertTrue(out.isNotEmpty())
    }
}
