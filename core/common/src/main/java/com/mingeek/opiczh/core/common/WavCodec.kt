package com.mingeek.opiczh.core.common

import java.io.ByteArrayOutputStream

/**
 * Gemini TTS는 원시 PCM(L16)을 돌려주므로, 로컬 플레이어가 재생할 수 있게
 * 표준 WAV(RIFF) 헤더를 붙인다.
 */
object WavCodec {

    fun pcmToWav(
        pcm: ByteArray,
        sampleRate: Int,
        channels: Int = 1,
        bitsPerSample: Int = 16,
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val out = ByteArrayOutputStream(pcm.size + 44)

        fun writeString(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun writeIntLe(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF)
            out.write((v shr 24) and 0xFF)
        }
        fun writeShortLe(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }

        writeString("RIFF")
        writeIntLe(36 + pcm.size)
        writeString("WAVE")
        writeString("fmt ")
        writeIntLe(16)            // fmt 청크 크기
        writeShortLe(1)           // PCM
        writeShortLe(channels)
        writeIntLe(sampleRate)
        writeIntLe(byteRate)
        writeShortLe(blockAlign)
        writeShortLe(bitsPerSample)
        writeString("data")
        writeIntLe(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }

    /** "audio/L16;codec=pcm;rate=24000" 형태의 mimeType에서 샘플레이트 추출 */
    fun sampleRateFromMime(mimeType: String?, default: Int = 24_000): Int {
        if (mimeType == null) return default
        val match = Regex("rate=(\\d+)").find(mimeType) ?: return default
        return match.groupValues[1].toIntOrNull() ?: default
    }
}
