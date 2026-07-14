package com.mingeek.opiczh.core.ai

import com.mingeek.opiczh.core.common.AppError
import com.mingeek.opiczh.core.common.AppResult
import com.mingeek.opiczh.core.common.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 쉐도잉용: 원문 대비 발음·성조 피드백 (한국어 코멘트) */
@Singleton
class PronunciationCoach @Inject constructor(
    private val router: LlmRouter,
) {

    suspend fun coach(referenceText: String, audioFile: File): AppResult<String> {
        val bytes = withContext(Dispatchers.IO) {
            runCatching { audioFile.readBytes() }.getOrNull()
        } ?: return AppResult.failure(AppError.Audio("녹음 파일을 읽을 수 없습니다"))

        val prompt = """
            [원문]
            $referenceText

            첨부된 오디오는 한국인 학습자가 위 원문을 따라 읽은(쉐도잉) 것입니다.
            원문과 비교해 다음을 한국어로 간결하게 피드백하세요:
            1. 잘 발음한 부분 한 가지 (칭찬)
            2. 발음·성조가 틀리거나 어색한 단어들: 단어 → 올바른 병음(성조 포함) → 교정 팁
            3. 전체적인 억양·속도에 대한 조언 한 줄
            빠뜨리거나 다르게 말한 단어가 있으면 지적하세요.
            형식 없이 자연스러운 코치의 말투로, 6줄 이내.
        """.trimIndent()

        return router.generate(
            AiTask.DRILL_FEEDBACK,
            LlmRequest(
                parts = listOf(
                    LlmPart.Audio(bytes = bytes, mimeType = "audio/mp4"),
                    LlmPart.Text(prompt),
                ),
                temperature = 0.3f,
            ),
        ).map { it.text.trim() }
    }
}
