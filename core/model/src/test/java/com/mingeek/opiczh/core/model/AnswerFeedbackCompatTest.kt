package com.mingeek.opiczh.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * provisional/transcribedBy 필드의 저장 호환 계약을 고정한다.
 * ExamRepository가 `Json { ignoreUnknownKeys = true }`(encodeDefaults=false)로
 * feedbackJson을 저장하고, ExamDao가 `%"provisional":true%` LIKE로 임시 채점을
 * 판별하므로 — 이 리터럴과 하위호환이 깨지면 대기함 계산이 틀어진다.
 */
class AnswerFeedbackCompatTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `구버전 JSON(신필드 없음)은 기본값으로 decode`() {
        val legacy = """{"transcript":"你好","estimatedGrade":"IM2"}"""
        val feedback = json.decodeFromString(AnswerFeedback.serializer(), legacy)
        assertFalse(feedback.provisional)
        assertNull(feedback.transcribedBy)
        assertEquals(OpicGrade.IM2, feedback.estimatedGrade)
    }

    @Test
    fun `provisional=true는 정확히 이 리터럴로 직렬화된다 (DAO LIKE 계약)`() {
        val feedback = AnswerFeedback(
            transcript = "我喜欢看电影",
            estimatedGrade = OpicGrade.IM1,
            provisional = true,
            transcribedBy = "sense-voice-int8",
        )
        val encoded = json.encodeToString(AnswerFeedback.serializer(), feedback)
        assertTrue("직렬화에 리터럴 누락: $encoded", encoded.contains("\"provisional\":true"))
    }

    @Test
    fun `provisional=false(기본값)는 직렬화에 나타나지 않는다`() {
        val feedback = AnswerFeedback(transcript = "你好", estimatedGrade = OpicGrade.IM2)
        val encoded = json.encodeToString(AnswerFeedback.serializer(), feedback)
        assertFalse(encoded.contains("provisional"))
    }

    @Test
    fun `임시 채점 왕복 보존`() {
        val original = AnswerFeedback(
            transcript = "我每天学习中文",
            estimatedGrade = OpicGrade.IM1,
            axes = listOf(AxisScore(RubricAxis.TASK_COMPLETION, 6, "무난")),
            provisional = true,
            transcribedBy = "sense-voice-int8",
            gradedBy = "qwen3-4b-instruct",
        )
        val decoded = json.decodeFromString(
            AnswerFeedback.serializer(),
            json.encodeToString(AnswerFeedback.serializer(), original),
        )
        assertEquals(original, decoded)
    }
}
