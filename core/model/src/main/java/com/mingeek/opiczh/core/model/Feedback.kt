package com.mingeek.opiczh.core.model

import kotlinx.serialization.Serializable

/** 채점 루브릭 축 (ACTFL 기준 근사) */
@Serializable
enum class RubricAxis(val ko: String) {
    TASK_COMPLETION("과제 수행"),
    SENTENCE_QUALITY("문장 수준"),
    VOCABULARY("어휘 다양성"),
    FLUENCY("유창성"),
    PRONUNCIATION("발음·성조"),
    LENGTH("답변 분량"),
}

@Serializable
data class AxisScore(
    val axis: RubricAxis,
    /** 1~10 */
    val score: Int,
    val comment: String = "",
)

/** 문장 단위 교정 */
@Serializable
data class Correction(
    val original: String,
    val corrected: String,
    val reason: String,
)

/** 목표 등급 수준의 모범답안 */
@Serializable
data class ModelAnswer(
    val zh: String,
    val pinyin: String = "",
    val ko: String = "",
)

/** 한 답변에 대한 AI 채점 결과 */
@Serializable
data class AnswerFeedback(
    /** 녹음에서 전사한 중국어 텍스트 */
    val transcript: String,
    val estimatedGrade: OpicGrade,
    /** 추정 범위 (보수적 표시용) */
    val gradeLow: OpicGrade = estimatedGrade,
    val gradeHigh: OpicGrade = estimatedGrade,
    val axes: List<AxisScore> = emptyList(),
    val corrections: List<Correction> = emptyList(),
    val modelAnswer: ModelAnswer? = null,
    /** 복습 큐로 유입되는 약점 태그 (예: "성조-3성", "과거시제") */
    val weaknessTags: List<String> = emptyList(),
    val advice: String = "",
    /**
     * 이 답변을 실제로 채점한 모델 ID. LLM 응답 스키마에는 없고 파싱 후 앱이 스탬프한다.
     * 자동 모델 전환으로 하위 모델이 채점했는지 리포트에서 확인하는 용도.
     */
    val gradedBy: String? = null,
)
