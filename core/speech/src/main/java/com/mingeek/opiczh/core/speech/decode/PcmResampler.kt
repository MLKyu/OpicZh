package com.mingeek.opiczh.core.speech.decode

/** PCM 후처리 순수 로직 — [AudioFileDecoder]가 코덱 출력에 적용한다 */
object PcmResampler {

    /** 인터리브드 PCM16 → mono float(-1..1). 다채널은 채널 평균으로 다운믹스 */
    fun toMonoFloat(pcm16: ShortArray, channels: Int): FloatArray {
        require(channels > 0) { "채널 수가 0 이하입니다" }
        if (channels == 1) {
            return FloatArray(pcm16.size) { pcm16[it] / 32768f }
        }
        val frames = pcm16.size / channels
        return FloatArray(frames) { frame ->
            var sum = 0f
            repeat(channels) { ch -> sum += pcm16[frame * channels + ch] }
            sum / channels / 32768f
        }
    }

    /** 선형 보간 리샘플 — 음성 인식 입력 용도로 충분한 품질 */
    fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        require(fromRate > 0 && toRate > 0) { "샘플레이트가 0 이하입니다" }
        if (fromRate == toRate || input.isEmpty()) return input
        val outSize = ((input.size.toLong() * toRate) / fromRate).toInt().coerceAtLeast(1)
        val ratio = fromRate.toDouble() / toRate
        return FloatArray(outSize) { i ->
            val pos = i * ratio
            val left = pos.toInt().coerceAtMost(input.lastIndex)
            val right = (left + 1).coerceAtMost(input.lastIndex)
            val frac = (pos - left).toFloat()
            input[left] * (1f - frac) + input[right] * frac
        }
    }
}
