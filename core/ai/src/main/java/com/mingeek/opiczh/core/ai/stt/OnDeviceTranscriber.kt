package com.mingeek.opiczh.core.ai.stt

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.mingeek.opiczh.core.common.AppError
import com.mingeek.opiczh.core.common.AppResult
import com.mingeek.opiczh.core.common.AppTracer
import com.mingeek.opiczh.core.speech.decode.AudioFileDecoder
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * sherpa-onnx SenseVoice 온디바이스 전사 — 클라우드(Gemini) 한도 소진·오프라인·
 * ON_DEVICE_ONLY에서 음성 흐름을 텍스트 경로로 우회시키는 다리.
 *
 * SenseVoice는 비스트리밍(문장 단위) 모델이라 30초 초과 녹음은 Silero VAD로 발화
 * 세그먼트를 나눠 인식한 뒤 이어붙인다. VAD가 런타임에 실패하면 30초 고정 윈도우로
 * 폴백해 전사 자체는 계속한다. [OnDeviceLlmEngine]과 같은 Mutex+지연로드+[unload] 패턴.
 */
@Singleton
class OnDeviceTranscriber @Inject constructor(
    private val sttModels: SttModelManager,
    private val decoder: AudioFileDecoder,
    private val tracer: AppTracer,
) {

    private var recognizer: OfflineRecognizer? = null
    private val engineMutex = Mutex()

    fun isReady(): Boolean = sttModels.isInstalled()

    /** m4a → 16kHz float → (분할) → 인식. 성공 결과는 항상 non-blank 전사문이다. */
    suspend fun transcribe(audioFile: File): AppResult<String> = tracer.trace("stt_transcribe") {
        if (!isReady()) {
            return@trace AppResult.failure(
                AppError.OnDeviceUnavailable(
                    "음성 인식 모델이 설치되어 있지 않습니다 — 설정 > 음성 인식 모델에서 다운로드해 주세요.",
                ),
            )
        }
        val samples = when (val decoded = decoder.decodeToPcm16k(audioFile)) {
            is AppResult.Success -> decoded.value
            is AppResult.Failure -> return@trace decoded
        }

        withContext(Dispatchers.Default) {
            try {
                val engine = engineMutex.withLock { obtainRecognizer() }
                val text = if (samples.size <= DIRECT_DECODE_MAX_SAMPLES) {
                    recognizeSegment(engine, samples)
                } else {
                    splitToSegments(samples).joinToString("") { recognizeSegment(engine, it) }
                }.trim()

                if (text.isBlank()) {
                    AppResult.failure(AppError.Audio("음성이 감지되지 않았습니다 — 녹음 상태를 확인해 주세요."))
                } else {
                    AppResult.success(text)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                AppResult.failure(AppError.Audio("온디바이스 전사 실패: ${t.message}"))
            }
        }
    }

    /** 시험 임시 채점의 2단계 전환·설정의 모델 삭제 시 호출 — 네이티브 메모리 해제 */
    suspend fun unload() {
        engineMutex.withLock {
            recognizer?.release()
            recognizer = null
        }
    }

    private fun obtainRecognizer(): OfflineRecognizer {
        recognizer?.let { return it }
        val config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = sttModels.file(SttModels.SENSE_VOICE).absolutePath,
                    language = "zh",
                    useInverseTextNormalization = true,
                ),
                tokens = sttModels.file(SttModels.SENSE_VOICE_TOKENS).absolutePath,
                numThreads = NUM_THREADS,
                provider = "cpu",
                modelType = "sense_voice",
            ),
        )
        return OfflineRecognizer(assetManager = null, config = config).also { recognizer = it }
    }

    private fun recognizeSegment(engine: OfflineRecognizer, samples: FloatArray): String {
        if (samples.isEmpty()) return ""
        val stream = engine.createStream()
        return try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            engine.decode(stream)
            engine.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    /** Silero VAD로 발화 세그먼트 분할. 실패하면 30초 고정 윈도우 폴백 */
    private fun splitToSegments(samples: FloatArray): List<FloatArray> =
        runCatching { splitWithVad(samples) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: samples.splitFixed(FIXED_WINDOW_SAMPLES)

    private fun splitWithVad(samples: FloatArray): List<FloatArray> {
        val vad = Vad(
            assetManager = null,
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = sttModels.file(SttModels.SILERO_VAD).absolutePath,
                    threshold = 0.5f,
                    minSilenceDuration = 0.25f,
                    minSpeechDuration = 0.25f,
                    windowSize = VAD_WINDOW_SIZE,
                    // 한 세그먼트가 SenseVoice 안전 범위를 넘지 않게 강제 분할
                    maxSpeechDuration = DIRECT_DECODE_MAX_SECONDS.toFloat(),
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
                provider = "cpu",
                debug = false,
            ),
        )
        return try {
            val segments = ArrayList<FloatArray>()
            var offset = 0
            while (offset + VAD_WINDOW_SIZE <= samples.size) {
                vad.acceptWaveform(samples.copyOfRange(offset, offset + VAD_WINDOW_SIZE))
                offset += VAD_WINDOW_SIZE
                while (!vad.empty()) {
                    segments.add(vad.front().samples)
                    vad.pop()
                }
            }
            vad.flush()
            while (!vad.empty()) {
                segments.add(vad.front().samples)
                vad.pop()
            }
            segments
        } finally {
            vad.release()
        }
    }

    private fun FloatArray.splitFixed(windowSamples: Int): List<FloatArray> {
        val out = ArrayList<FloatArray>((size + windowSamples - 1) / windowSamples)
        var start = 0
        while (start < size) {
            val end = minOf(start + windowSamples, size)
            out.add(copyOfRange(start, end))
            start = end
        }
        return out
    }

    companion object {
        /** AnswerFeedback.transcribedBy 스탬프 값 */
        const val ENGINE_ID = "sense-voice-int8"

        private const val SAMPLE_RATE = AudioFileDecoder.TARGET_SAMPLE_RATE
        private const val NUM_THREADS = 4
        private const val DIRECT_DECODE_MAX_SECONDS = 30
        private const val DIRECT_DECODE_MAX_SAMPLES = SAMPLE_RATE * DIRECT_DECODE_MAX_SECONDS
        private const val FIXED_WINDOW_SAMPLES = DIRECT_DECODE_MAX_SAMPLES

        /** Silero VAD 고정 윈도우 (16kHz 기준 512 샘플) */
        private const val VAD_WINDOW_SIZE = 512
    }
}
