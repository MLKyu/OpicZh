package com.mingeek.opiczh.core.ai.gemini

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RequestPacerTest {

    @Test
    fun `first calls within the window pass immediately`() = runTest {
        val pacer = RequestPacer(nowMs = { currentTime })
        val start = currentTime
        repeat(8) { pacer.awaitSlot("gemini-3.5-flash") } // flash 기본 8/분
        assertEquals(start, currentTime)
    }

    @Test
    fun `call over the per-minute limit waits for the window`() = runTest {
        val pacer = RequestPacer(nowMs = { currentTime })
        repeat(8) { pacer.awaitSlot("gemini-3.5-flash") }
        val before = currentTime
        pacer.awaitSlot("gemini-3.5-flash") // 9번째 → 창이 열릴 때까지 대기
        assertTrue(currentTime - before >= 60_000)
    }

    @Test
    fun `models are paced independently`() = runTest {
        val pacer = RequestPacer(nowMs = { currentTime })
        repeat(8) { pacer.awaitSlot("gemini-3.5-flash") }
        val before = currentTime
        pacer.awaitSlot("gemini-3.1-flash-lite") // 다른 모델은 별도 창
        assertEquals(before, currentTime)
    }

    @Test
    fun `tts models use the conservative 3 per minute limit`() = runTest {
        val pacer = RequestPacer(nowMs = { currentTime })
        repeat(3) { pacer.awaitSlot("gemini-3.1-flash-tts-preview") }
        val before = currentTime
        pacer.awaitSlot("gemini-3.1-flash-tts-preview")
        assertTrue(currentTime - before >= 60_000)
    }
}
