package com.mingeek.opiczh.core.speech.decode

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.mingeek.opiczh.core.common.AppError
import com.mingeek.opiczh.core.common.AppResult
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 압축 오디오 파일(m4a/AAC 등) → 16kHz mono float PCM(-1..1).
 * 온디바이스 STT(SenseVoice)의 입력을 만든다. 녹음기(AnswerRecorder)가 이미
 * 16kHz mono로 녹음하므로 리샘플·다운믹스는 방어 코드다.
 */
@Singleton
class AudioFileDecoder @Inject constructor() {

    suspend fun decodeToPcm16k(file: File): AppResult<FloatArray> = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() == 0L) {
            return@withContext AppResult.failure(
                AppError.Audio("녹음 파일이 없거나 비어 있습니다: ${file.name}"),
            )
        }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val (trackIndex, format) = findAudioTrack(extractor)
                ?: return@withContext AppResult.failure(
                    AppError.Audio("오디오 트랙을 찾을 수 없습니다: ${file.name}"),
                )

            val durationUs = runCatching { format.getLong(MediaFormat.KEY_DURATION) }.getOrDefault(0L)
            if (durationUs > MAX_DURATION_US) {
                return@withContext AppResult.failure(
                    AppError.Audio("녹음이 너무 깁니다 (${durationUs / 60_000_000}분) — 15분 이하만 전사할 수 있습니다"),
                )
            }

            extractor.selectTrack(trackIndex)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }
            val pcm = drainDecoder(extractor, codec, format)
            if (pcm.isEmpty()) {
                AppResult.failure(AppError.Audio("디코딩 결과가 비어 있습니다: ${file.name}"))
            } else {
                AppResult.success(pcm)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            AppResult.failure(AppError.Audio("오디오 디코딩 실패: ${t.message}"))
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun findAudioTrack(extractor: MediaExtractor): Pair<Int, MediaFormat>? {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            if (format.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                return i to format
            }
        }
        return null
    }

    /** 동기 디코드 루프 — EOS까지 PCM16 청크를 모아 mono float 16kHz로 변환 */
    private fun drainDecoder(
        extractor: MediaExtractor,
        codec: MediaCodec,
        inputFormat: MediaFormat,
    ): FloatArray {
        val chunks = ArrayList<ShortArray>()
        val info = MediaCodec.BufferInfo()
        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var inputDone = false
        var outputDone = false

        while (!outputDone) {
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inIndex >= 0) {
                    val buffer = requireNotNull(codec.getInputBuffer(inIndex))
                    val sampleSize = extractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            when (val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val outFormat = codec.outputFormat
                    sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                else -> if (outIndex >= 0) {
                    if (info.size > 0) {
                        val buffer = requireNotNull(codec.getOutputBuffer(outIndex))
                        val shorts = ShortArray(info.size / 2)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.asShortBuffer().get(shorts)
                        chunks.add(shorts)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
            }
        }

        val total = chunks.sumOf { it.size }
        val merged = ShortArray(total)
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(merged, offset)
            offset += chunk.size
        }
        val mono = PcmResampler.toMonoFloat(merged, channels)
        return PcmResampler.resample(mono, sampleRate, TARGET_SAMPLE_RATE)
    }

    companion object {
        const val TARGET_SAMPLE_RATE = 16_000
        private const val TIMEOUT_US = 10_000L
        private const val MAX_DURATION_US = 15L * 60 * 1_000_000
    }
}
