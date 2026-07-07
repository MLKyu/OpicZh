package com.mingeek.opiczh.core.ai

import com.mingeek.opiczh.core.common.AppError
import com.mingeek.opiczh.core.common.AppResult
import com.mingeek.opiczh.core.common.AppTracer
import com.mingeek.opiczh.core.common.flatMap
import com.mingeek.opiczh.core.common.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 녹음 답변(m4a)을 간체 중국어 텍스트로 전사한다. */
@Singleton
class AnswerTranscriber @Inject constructor(
    private val router: LlmRouter,
    private val tracer: AppTracer,
) {

    suspend fun transcribe(audioFile: File): AppResult<String> {
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
            router.engineFor(AiTask.TRANSCRIPTION)
                .flatMap { engine -> engine.generate(request) }
                .map { reply -> reply.text.trim() }
        }
    }

    private companion object {
        /** Gemini 인라인 오디오 한도(20MB)에서 여유를 둔 값 */
        const val MAX_INLINE_BYTES = 18 * 1024 * 1024

        val PROMPT = """
            이 오디오는 한국인 학습자가 말한 중국어 답변입니다.
            들리는 대로 간체 중국어로 정확하게 전사하세요.
            발음이 불명확한 부분은 문맥상 가장 가능성 높은 표현으로 추정해 전사하세요.
            설명이나 주석 없이 전사한 중국어 텍스트만 출력하세요.
        """.trimIndent()
    }
}
