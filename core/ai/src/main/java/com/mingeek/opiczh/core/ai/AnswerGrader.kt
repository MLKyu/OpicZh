package com.mingeek.opiczh.core.ai

import com.mingeek.opiczh.core.ai.stt.OnDeviceTranscriber
import com.mingeek.opiczh.core.ai.stt.SttBypassPolicy
import com.mingeek.opiczh.core.common.AppError
import com.mingeek.opiczh.core.common.AppResult
import com.mingeek.opiczh.core.common.AppTracer
import com.mingeek.opiczh.core.common.RemoteTuning
import com.mingeek.opiczh.core.common.flatMap
import com.mingeek.opiczh.core.common.map
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
 * 답변 채점.
 * - [grade]: 답변 오디오를 전사+채점까지 한 번의 호출로 처리 (음성 입력 = 클라우드 전용).
 *   sttBypass=true면 클라우드 불가 시 온디바이스 STT 전사 → 텍스트 채점으로 우회한다
 *   (기본 false — 모의고사는 자동 우회 없이 채점 대기함에 보존).
 * - [gradeText]: 타이핑한 답변을 텍스트로 채점 — 오디오가 없어 LlmRouter의 온디바이스
 *   경로(ON_DEVICE_ONLY, AUTO의 한도 초과 폴백)로도 동작한다
 * responseSchema로 JSON 출력을 강제하고 [AnswerFeedback]으로 파싱한다.
 */
@Singleton
class AnswerGrader @Inject constructor(
    private val router: LlmRouter,
    private val onDeviceTranscriber: OnDeviceTranscriber,
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
        sttBypass: Boolean = false,
    ): AppResult<AnswerFeedback> = tracer.trace(
        "grade_answer",
        "question_type" to question.type.name,
    ) {
        val cloud = gradeInternal(question, audioFile, target)
        if (cloud is AppResult.Success || !sttBypass) return@trace cloud

        val error = (cloud as AppResult.Failure).error
        if (!SttBypassPolicy.shouldBypass(error, onDeviceTranscriber.isReady())) return@trace cloud

        // 우회까지 실패하면 우회 경로의 오류를 반환한다 — gradeText가 RateLimited로
        // 실패했다면 그 retryAfterSec 힌트가 살아 있고, STT 실패(무음 등)는 더 행동 가능하다.
        gradeViaOnDeviceStt(question, audioFile, target)
    }

    /**
     * 온디바이스 STT 전사 → 텍스트 4축 채점(채점 LLM은 라우터가 결정) → 임시 표식.
     * 모의고사 대기함의 "온디바이스 임시 채점"과 [grade]의 자동 우회가 함께 쓴다.
     */
    suspend fun gradeViaOnDeviceStt(
        question: Question,
        audioFile: File,
        target: TargetGrade,
    ): AppResult<AnswerFeedback> =
        onDeviceTranscriber.transcribe(audioFile).flatMap { transcript ->
            gradeTranscribedProvisional(question, transcript, target)
        }

    /** 이미 전사된 텍스트의 임시 채점 — 시험 임시 채점 패스가 전사/채점을 2단계로 나눌 때 사용 */
    suspend fun gradeTranscribedProvisional(
        question: Question,
        transcript: String,
        target: TargetGrade,
    ): AppResult<AnswerFeedback> =
        gradeText(question, transcript, target, fromSpeech = true).map { feedback ->
            feedback.copy(provisional = true, transcribedBy = OnDeviceTranscriber.ENGINE_ID)
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

        return generateAndParse(request)
    }

    /**
     * 타이핑한 답변 채점. 전사가 필요 없어 온디바이스 LLM으로도 채점된다 —
     * '온디바이스만' 모드와 클라우드 한도 소진 시에도 연습을 이어가는 경로.
     * 발음·유창성 축은 텍스트로 판단할 수 없어 채점에서 제외한다.
     */
    suspend fun gradeText(
        question: Question,
        answerText: String,
        target: TargetGrade,
        fromSpeech: Boolean = false,
    ): AppResult<AnswerFeedback> = tracer.trace(
        "grade_answer_text",
        "question_type" to question.type.name,
    ) {
        val request = LlmRequest(
            parts = listOf(LlmPart.Text(buildTextPrompt(question, target, answerText, fromSpeech))),
            systemPrompt = remoteTuning.string(RemoteTuning.Keys.GRADING_SYSTEM_PROMPT)
                ?: SYSTEM_PROMPT,
            responseJsonSchema = FEEDBACK_SCHEMA,
            temperature = 0.2f,
            maxOutputTokens = 4_096,
        )
        // 답변 원문이 곧 전사다 — 모델이 다르게 옮겨 적어도 원문을 보존한다
        generateAndParse(request).map { feedback -> feedback.copy(transcript = answerText) }
    }

    /** 라우터 호출 + JSON 파싱. 파싱 실패는 LLM 비결정성일 수 있어 1회 재시도한다 */
    private suspend fun generateAndParse(request: LlmRequest): AppResult<AnswerFeedback> {
        var lastError: AppError? = null
        repeat(2) {
            val result = router.generate(AiTask.GRADING, request)
                .flatMap { reply ->
                    parseFeedback(reply.text)
                        // 스키마 밖 필드: 어느 모델이 채점했는지 스탬프 (자동 체인 전환 추적)
                        .map { feedback -> feedback.copy(gradedBy = reply.modelId) }
                }
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

    private fun parseFeedback(text: String): AppResult<AnswerFeedback> {
        val direct = runCatching {
            json.decodeFromString(AnswerFeedback.serializer(), text.trim())
        }
        direct.getOrNull()?.let { return AppResult.success(it) }

        // 온디바이스 모델은 responseSchema 강제가 없어 ```json 펜스·설명 문장을 붙이기도 한다
        // — 본문에서 JSON 오브젝트만 추려 한 번 더 시도한다.
        val salvaged = extractJsonObject(text)?.let { candidate ->
            runCatching { json.decodeFromString(AnswerFeedback.serializer(), candidate) }.getOrNull()
        }
        return salvaged?.let { AppResult.success(it) }
            ?: AppResult.failure(AppError.Parsing("채점 결과 해석 실패: ${direct.exceptionOrNull()?.message}"))
    }

    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start in 0 until end) text.substring(start, end + 1) else null
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

    private fun buildTextPrompt(
        question: Question,
        target: TargetGrade,
        answerText: String,
        fromSpeech: Boolean = false,
    ): String = """
        [문항 정보]
        - 유형: ${question.type.ko}
        - 난이도: ${question.difficulty} (1~6)
        - 질문(중국어): ${question.zh}
        ${question.ko?.let { "- 질문(한국어): $it" } ?: ""}

        [수험자 정보]
        - 목표 등급: ${target.display}

        ${if (fromSpeech) "[수험자 답변 — 음성 인식(STT) 전사본]" else "[수험자 답변 — 텍스트로 작성됨]"}
        $answerText

        위 텍스트는 이 질문에 대한 수험자의 중국어 답변입니다. 루브릭에 따라 채점한 뒤
        지정된 JSON 스키마로만 응답하세요.
        ${if (fromSpeech) {
        "주의: 이 답변은 음성 인식으로 전사된 텍스트라 동음(谐音) 오인식이 섞여 있을 수 있습니다. " +
            "문맥상 명백한 오인식으로 보이는 표기는 수험자의 오류로 취급하지 말고(교정 대상 제외) " +
            "문맥으로 이해해 채점하세요."
    } else {
        ""
    }}

        [채점 지침]
        - transcript: 위 답변 텍스트를 그대로 넣기
        - estimatedGrade: 이 답변 하나만 놓고 본 ACTFL OPIc 추정 등급 (NL~AL, 한국식 IM1/IM2/IM3 세분 적용)
        - gradeLow/gradeHigh: 보수적 추정 범위
        - axes: 텍스트 답변이므로 FLUENCY·PRONUNCIATION은 제외하고 다음 4개 축만 1~10점.
          TASK_COMPLETION(질문이 요구한 과제를 다 수행했는가),
          SENTENCE_QUALITY(단문 나열인가, 연결어로 이어진 복문인가),
          VOCABULARY(어휘 다양성·적절성), LENGTH(답변 분량)
        - corrections: 실제 답변 문장에서 고칠 부분만. original은 답변에서 그대로 인용
        - modelAnswer: 목표 등급 ${target.display} 수준에 딱 맞는 모범답안.
          zh(간체), pinyin(성조 부호 포함), ko(한국어 번역) 모두 채울 것.
        - weaknessTags: 반복 학습이 필요한 약점을 짧은 한국어 태그로
        - advice: 목표 등급으로 끌어올리기 위한 구체적 조언 2~3문장 (한국어)
        - 답변이 중국어가 아니거나 비어 있으면: estimatedGrade는 NL
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
