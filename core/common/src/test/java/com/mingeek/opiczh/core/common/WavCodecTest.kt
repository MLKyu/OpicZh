package com.mingeek.opiczh.core.common

import org.junit.Assert.assertEquals
import org.junit.Test

class WavCodecTest {

    @Test
    fun `wav header is well formed`() {
        val pcm = ByteArray(1000) { (it % 256).toByte() }
        val wav = WavCodec.pcmToWav(pcm, sampleRate = 24_000)

        assertEquals(1044, wav.size)
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(wav, 36, 4, Charsets.US_ASCII))

        fun intLe(offset: Int): Int =
            (wav[offset].toInt() and 0xFF) or
                ((wav[offset + 1].toInt() and 0xFF) shl 8) or
                ((wav[offset + 2].toInt() and 0xFF) shl 16) or
                ((wav[offset + 3].toInt() and 0xFF) shl 24)

        assertEquals(36 + 1000, intLe(4))     // RIFF 크기
        assertEquals(24_000, intLe(24))       // 샘플레이트
        assertEquals(24_000 * 2, intLe(28))   // 바이트레이트 (mono 16bit)
        assertEquals(1000, intLe(40))         // data 크기
    }

    @Test
    fun `sample rate parsed from gemini mime type`() {
        assertEquals(24_000, WavCodec.sampleRateFromMime("audio/L16;codec=pcm;rate=24000"))
        assertEquals(16_000, WavCodec.sampleRateFromMime("audio/L16;rate=16000"))
        assertEquals(24_000, WavCodec.sampleRateFromMime("audio/L16"))
        assertEquals(24_000, WavCodec.sampleRateFromMime(null))
    }
}
