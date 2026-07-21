package com.mingeek.opiczh.core.ai

import com.mingeek.opiczh.core.ai.stt.OnDeviceTranscriber
import com.mingeek.opiczh.core.ai.stt.SttBypassPolicy
import com.mingeek.opiczh.core.common.AppError
import com.mingeek.opiczh.core.common.AppResult
import com.mingeek.opiczh.core.common.AppTracer
import com.mingeek.opiczh.core.common.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 전사를 수행한 주체 — UI가 "(온디바이스)" 라벨을 붙일 때 쓴다 */
enum class TranscriptionSource { CLOUD, ON_DEVICE }

data class Transcription(
    val text: String,
    val source: TranscriptionSource,
)

/**
 * 녹음 답변(m4a)을 간체 중국어 텍스트로 전사한다.
 * 클라우드(Gemini) 우선, 클라우드를 지금 못 쓰는 오류(한도 소진·키 없음·오프라인·
 * ON_DEVICE_ONLY 선판정)면 온디바이스 STT가 이어받는다 — 자유회화 음성 입력과
 * SpeechLab이 이 폴백을 그대로 얻는다.
 */
@Singleton
class AnswerTranscriber @Inject constructor(
    private val router: LlmRouter,
    private val onDeviceTranscriber: OnDeviceTranscriber,
    private val tracer: AppTracer,
) {

    suspend fun transcribe(audioFile: File): AppResult<Transcription> {
        val cloud = transcribeCloud(audioFile)
        if (cloud is AppResult.Success) return cloud

        val error = (cloud as AppResult.Failure).error
        if (!SttBypassPolicy.shouldBypass(error, onDeviceTranscriber.isReady())) {
            // STT 미설치이거나 우회 불가 오류 — 원래 오류 그대로 (RateLimited 힌트 보존)
            return cloud
        }
        return when (val stt = onDeviceTranscriber.transcribe(audioFile)) {
            is AppResult.Success ->
                AppResult.success(Transcription(stt.value, TranscriptionSource.ON_DEVICE))
            // STT까지 실패하면 STT 쪽 오류가 더 행동 가능하다 (무음·디코드 실패 등)
            is AppResult.Failure -> stt
        }
    }

    private suspend fun transcribeCloud(audioFile: File): AppResult<Transcription> {
        val bytes = withContext(Dispatchers.IO) {
            runCatching { audioFile.readBytes() }.getOrNull()
        } ?: return AppResult.failure(AppError.Audio("녹음 파일을 읽을 수 없습니다"))

        if (bytes.isEmpty()) {
            return AppResult.failure(AppError.Audio("녹음 파일이 비어 있습니다"))
        }
        if (bytes.size > MAX_INLINE_BYTES) {
            return AppResult.failure(AppError.Audio("녹음이 너무 깁니다 (${bytes.size / 1_000_000}MB)"))
        }

        val request = LlmRequest(
            parts = listOf(
                LlmPart.Audio(bytes = bytes, mimeType = "audio/mp4"),
                LlmPart.Text(PROMPT),
            ),
            temperature = 0.1f,
        )
        return tracer.trace("transcribe_answer", "audio_kb" to (bytes.size / 1024).toString()) {
            router.generate(AiTask.TRANSCRIPTION, request)
                .map { reply -> Transcription(reply.text.trim(), TranscriptionSource.CLOUD) }
        }
    }

    private companion object {
        /** Gemini 인라인 오디오 한도(20MB)에서 여유를 둔 값 — 클라우드 경로에만 적용 */
        const val MAX_INLINE_BYTES = 18 * 1024 * 1024

        val PROMPT = """
            이 오디오는 한국인 학습자가 말한 중국어 답변입니다.
            들리는 대로 간체 중국어로 정확하게 전사하세요.
            발음이 불명확한 부분은 문맥상 가장 가능성 높은 표현으로 추정해 전사하세요.
            설명이나 주석 없이 전사한 중국어 텍스트만 출력하세요.
        """.trimIndent()
    }
}
