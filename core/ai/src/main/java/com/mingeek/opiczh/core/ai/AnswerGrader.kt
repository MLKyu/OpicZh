package com.mingeek.opiczh.core.ai

import com.mingeek.opiczh.core.common.AppError
import com.mingeek.opiczh.core.common.AppResult
import com.mingeek.opiczh.core.common.AppTracer
import com.mingeek.opiczh.core.common.RemoteTuning
import com.mingeek.opiczh.core.common.errorOrNull
import com.mingeek.opiczh.core.common.flatMap
import com.mingeek.opiczh.core.model.AnswerFeedback
import com.mingeek.opiczh.core.model.OpicGrade
import com.mingeek.opiczh.core.model.Question
import com.mingeek.opiczh.core.model.RubricAxis
import com.mingeek.opiczh.core.model.TargetGrade
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * 답변 오디오를 전사+채점까지 한 번의 Gemini 호출로 처리한다.
 * responseSchema로 JSON 출력을 강제하고 [AnswerFeedback]으로 파싱한다.
 */
@Singleton
class AnswerGrader @Inject constructor(
    private val router: LlmRouter,
    private val tracer: AppTracer,
    private val remoteTuning: RemoteTuning,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    suspend fun grade(
        question: Question,
        audioFile: File,
        target: TargetGrade,
    ): AppResult<AnswerFeedback> = tracer.trace(
        "grade_answer",
        "question_type" to question.type.name,
    ) {
        gradeInternal(question, audioFile, target)
    }

    private suspend fun gradeInternal(
        question: Question,
        audioFile: File,
        target: TargetGrade,
    ): AppResult<AnswerFeedback> {
        val bytes = withContext(Dispatchers.IO) {
            runCatching { audioFile.readBytes() }.getOrNull()
        } ?: return AppResult.failure(AppError.Audio("답변 녹음 파일을 읽을 수 없습니다"))

        val request = LlmRequest(
            parts = listOf(
                LlmPart.Audio(bytes = bytes, mimeType = "audio/mp4"),
                LlmPart.Text(buildPrompt(question, target)),
            ),
            // Remote Config로 채점 프롬프트를 재빌드 없이 교체할 수 있다
            systemPrompt = remoteTuning.string(RemoteTuning.Keys.GRADING_SYSTEM_PROMPT)
                ?: SYSTEM_PROMPT,
            responseJsonSchema = FEEDBACK_SCHEMA,
            temperature = 0.2f,
            maxOutputTokens = 8_192,
        )

        // 파싱 실패는 LLM 비결정성일 수 있어 1회 재시도한다
        var lastError: AppError? = null
        repeat(2) {
            val result = router.engineFor(AiTask.GRADING)
                .flatMap { engine -> engine.generate(request) }
                .flatMap { reply -> parseFeedback(reply.text) }
            when (result) {
                is AppResult.Success -> return result
                is AppResult.Failure -> {
                    lastError = result.error
                    if (result.error !is AppError.Parsing) return result
                }
            }
        }
        return AppResult.failure(lastError ?: AppError.Unknown("채점 실패"))
    }

    /** 녹음이 없는(건너뛴) 답변용 기본 피드백 */
    fun skippedFeedback(): AnswerFeedback = AnswerFeedback(
        transcript = "",
        estimatedGrade = OpicGrade.NL,
        axes = RubricAxis.entries.map {
            com.mingeek.opiczh.core.model.AxisScore(it, 1, "무응답")
        },
        advice = "답변을 건너뛰었습니다. 어떤 질문이든 침묵보다는 만능 문장으로라도 답하는 연습을 하세요.",
    )

    private fun parseFeedback(text: String): AppResult<AnswerFeedback> = try {
        AppResult.success(json.decodeFromString(AnswerFeedback.serializer(), text.trim()))
    } catch (t: Throwable) {
        AppResult.failure(AppError.Parsing("채점 결과 해석 실패: ${t.message}"))
    }

    private fun buildPrompt(question: Question, target: TargetGrade): String = """
        [문항 정보]
        - 유형: ${question.type.ko}
        - 난이도: ${question.difficulty} (1~6)
        - 질문(중국어): ${question.zh}
        ${question.ko?.let { "- 질문(한국어): $it" } ?: ""}

        [수험자 정보]
        - 목표 등급: ${target.display}

        첨부된 오디오는 이 질문에 대한 수험자의 중국어 답변입니다.
        1) 답변을 간체 중국어로 정확히 전사하고,
        2) 루브릭에 따라 채점한 뒤,
        3) 지정된 JSON 스키마로만 응답하세요.

        [채점 지침]
        - estimatedGrade: 이 답변 하나만 놓고 본 ACTFL OPIc 추정 등급 (NL~AL, 한국식 IM1/IM2/IM3 세분 적용)
        - gradeLow/gradeHigh: 보수적 추정 범위
        - axes: 6개 축 모두 1~10점. TASK_COMPLETION(질문이 요구한 과제를 다 수행했는가),
          SENTENCE_QUALITY(단문 나열인가, 연결어로 이어진 복문인가), VOCABULARY(어휘 다양성·적절성),
          FLUENCY(휴지·필러·속도), PRONUNCIATION(발음·성조 정확성), LENGTH(답변 분량)
        - corrections: 실제 발화 문장에서 고칠 부분만. original은 전사에서 그대로 인용
        - modelAnswer: 목표 등급 ${target.display} 수준에 딱 맞는 모범답안.
          zh(간체), pinyin(성조 부호 포함), ko(한국어 번역) 모두 채울 것.
          너무 어려운 표현 대신 목표 등급에서 실제로 구사 가능한 수준으로.
        - weaknessTags: 반복 학습이 필요한 약점을 짧은 한국어 태그로 (예: "성조-2성", "과거 경험 시제", "연결어 부족")
        - advice: 이 답변을 목표 등급으로 끌어올리기 위한 구체적 조언 2~3문장 (한국어)
        - 오디오가 무음이거나 중국어 답변이 없으면: transcript는 빈 문자열, estimatedGrade는 NL
        - comment/reason/advice는 한국어로 작성
    """.trimIndent()

    private companion object {
        val SYSTEM_PROMPT = """
            당신은 ACTFL OPIc 중국어 시험의 전문 채점관입니다.
            한국인 수험자의 답변을 실제 OPIc 기준(과제 수행, 텍스트 유형, 정확성)에 따라
            엄격하지만 공정하게 평가합니다. 점수를 후하게 주지 않습니다.
            반드시 요청된 JSON 스키마 형식으로만 응답합니다.
        """.trimIndent()

        val GRADE_ENUM = OpicGrade.entries.map { it.name }
        val AXIS_ENUM = RubricAxis.entries.map { it.name }

        val FEEDBACK_SCHEMA: JsonObject = buildJsonObject {
            put("type", "OBJECT")
            putJsonObject("properties") {
                putJsonObject("transcript") { put("type", "STRING") }
                putJsonObject("estimatedGrade") {
                    put("type", "STRING")
                    putJsonArray("enum") { GRADE_ENUM.forEach { add(it) } }
                }
                putJsonObject("gradeLow") {
                    put("type", "STRING")
                    putJsonArray("enum") { GRADE_ENUM.forEach { add(it) } }
                }
                putJsonObject("gradeHigh") {
                    put("type", "STRING")
                    putJsonArray("enum") { GRADE_ENUM.forEach { add(it) } }
                }
                putJsonObject("axes") {
                    put("type", "ARRAY")
                    putJsonObject("items") {
                        put("type", "OBJECT")
                        putJsonObject("properties") {
                            putJsonObject("axis") {
                                put("type", "STRING")
                                putJsonArray("enum") { AXIS_ENUM.forEach { add(it) } }
                            }
                            putJsonObject("score") { put("type", "INTEGER") }
                            putJsonObject("comment") { put("type", "STRING") }
                        }
                        putJsonArray("required") {
                            add("axis"); add("score"); add("comment")
                        }
                    }
                }
                putJsonObject("corrections") {
                    put("type", "ARRAY")
                    putJsonObject("items") {
                        put("type", "OBJECT")
                        putJsonObject("properties") {
                            putJsonObject("original") { put("type", "STRING") }
                            putJsonObject("corrected") { put("type", "STRING") }
                            putJsonObject("reason") { put("type", "STRING") }
                        }
                        putJsonArray("required") {
                            add("original"); add("corrected"); add("reason")
                        }
                    }
                }
                putJsonObject("modelAnswer") {
                    put("type", "OBJECT")
                    putJsonObject("properties") {
                        putJsonObject("zh") { put("type", "STRING") }
                        putJsonObject("pinyin") { put("type", "STRING") }
                        putJsonObject("ko") { put("type", "STRING") }
                    }
                    putJsonArray("required") { add("zh"); add("pinyin"); add("ko") }
                }
                putJsonObject("weaknessTags") {
                    put("type", "ARRAY")
                    putJsonObject("items") { put("type", "STRING") }
                }
                putJsonObject("advice") { put("type", "STRING") }
            }
            putJsonArray("required") {
                add("transcript"); add("estimatedGrade"); add("axes")
                add("corrections"); add("modelAnswer"); add("weaknessTags"); add("advice")
            }
        }
    }
}
