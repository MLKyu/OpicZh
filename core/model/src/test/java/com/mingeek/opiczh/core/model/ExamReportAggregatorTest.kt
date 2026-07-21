package com.mingeek.opiczh.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExamReportAggregatorTest {

    private val question = Question(
        id = "q1",
        topicId = "t1",
        type = QuestionType.DESCRIPTION,
        difficulty = 3,
        zh = "请描述一下你的房间。",
    )

    private fun feedback(axes: List<AxisScore>, grade: OpicGrade = OpicGrade.IM2) =
        AnswerFeedback(transcript = "答案", estimatedGrade = grade, axes = axes)

    private fun sixAxis(score: Int) = RubricAxis.entries.map { AxisScore(it, score) }

    private fun fourAxis(score: Int) = RubricAxis.entries
        .filterNot { it == RubricAxis.FLUENCY || it == RubricAxis.PRONUNCIATION }
        .map { AxisScore(it, score) }

    private fun graded(vararg feedbacks: AnswerFeedback) =
        feedbacks.mapIndexed { i, f -> GradedAnswer(i, question, f) }

    @Test
    fun `6축만 있으면 모든 축이 평균에 포함`() {
        val report = ExamReportAggregator.aggregate(
            graded(feedback(sixAxis(6)), feedback(sixAxis(8))),
            TargetGrade.IM2,
        )
        assertEquals(RubricAxis.entries.size, report.axisAverages.size)
        assertEquals(7.0, report.axisAverages.getValue(RubricAxis.PRONUNCIATION), 1e-9)
    }

    @Test
    fun `4축(임시 채점)만 있으면 발음·유창성 축이 맵에서 빠진다`() {
        val report = ExamReportAggregator.aggregate(
            graded(feedback(fourAxis(6)), feedback(fourAxis(8))),
            TargetGrade.IM2,
        )
        assertFalse(report.axisAverages.containsKey(RubricAxis.PRONUNCIATION))
        assertFalse(report.axisAverages.containsKey(RubricAxis.FLUENCY))
        assertEquals(7.0, report.axisAverages.getValue(RubricAxis.TASK_COMPLETION), 1e-9)
    }

    @Test
    fun `혼합 세트는 6축 답변들만으로 발음 평균을 낸다 - 0점 오염 없음`() {
        val report = ExamReportAggregator.aggregate(
            graded(feedback(sixAxis(8)), feedback(fourAxis(2))),
            TargetGrade.IM2,
        )
        // 이전 구현이라면 발음 축에 4축 답변이 0으로 섞여 평균이 내려갔다
        assertEquals(8.0, report.axisAverages.getValue(RubricAxis.PRONUNCIATION), 1e-9)
        assertEquals(5.0, report.axisAverages.getValue(RubricAxis.TASK_COMPLETION), 1e-9)
    }

    @Test
    fun `등급 집계는 축 구성과 무관하게 동작`() {
        val report = ExamReportAggregator.aggregate(
            graded(
                feedback(fourAxis(5), grade = OpicGrade.IM1),
                feedback(fourAxis(5), grade = OpicGrade.IM2),
                feedback(sixAxis(5), grade = OpicGrade.IM2),
            ),
            TargetGrade.IM1,
        )
        assertTrue(report.overallGrade.rank >= OpicGrade.IM1.rank)
        assertEquals(OpicGrade.IM1, report.gradeLow)
        assertEquals(OpicGrade.IM2, report.gradeHigh)
    }
}
