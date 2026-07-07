package com.mingeek.opiczh.core.model

import kotlinx.serialization.Serializable

/** OPIc 문항 유형. 실전 시험의 출제 슬롯과 1:1로 대응한다. */
@Serializable
enum class QuestionType(val ko: String) {
    SELF_INTRODUCTION("자기소개"),
    DESCRIPTION("묘사"),
    ROUTINE("습관/일상"),
    EXPERIENCE("경험"),
    COMPARISON("비교"),
    ROLEPLAY_ASK("롤플레이-질문하기"),
    ROLEPLAY_SOLVE("롤플레이-문제해결"),
    ROLEPLAY_EXPERIENCE("롤플레이-관련경험"),
    UNEXPECTED("돌발주제"),
}

/** Background Survey 주제(취미/여행 등) 또는 돌발 주제 카테고리 */
@Serializable
data class Topic(
    val id: String,
    val nameKo: String,
    val nameZh: String,
    /** true면 Background Survey에서 선택 가능한 주제, false면 돌발 주제 */
    val survey: Boolean = true,
)

/** 문제은행의 개별 문항 */
@Serializable
data class Question(
    val id: String,
    val topicId: String,
    val type: QuestionType,
    /** 실전 Self-Assessment 기준 난이도 1~6 */
    val difficulty: Int,
    /** 시험관(Ava 역)이 읽어주는 중국어 질문 전문 */
    val zh: String,
    val pinyin: String? = null,
    val ko: String? = null,
)
