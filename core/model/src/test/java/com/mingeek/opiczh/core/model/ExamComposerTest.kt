package com.mingeek.opiczh.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExamComposerTest {

    private fun q(id: String, topic: String, type: QuestionType, diff: Int) =
        Question(id = id, topicId = topic, type = type, difficulty = diff, zh = "问题$id")

    private val pool = buildList {
        add(q("intro1", "self_intro", QuestionType.SELF_INTRODUCTION, 3))
        listOf("home", "music", "park").forEach { topic ->
            listOf(2, 3, 4, 5).forEach { d ->
                add(q("${topic}_desc_$d", topic, QuestionType.DESCRIPTION, d))
                add(q("${topic}_routine_$d", topic, QuestionType.ROUTINE, d))
                add(q("${topic}_exp_$d", topic, QuestionType.EXPERIENCE, d))
                add(q("${topic}_comp_$d", topic, QuestionType.COMPARISON, d))
            }
        }
        listOf("ux_food", "ux_weather").forEach { topic ->
            repeat(4) { add(q("${topic}_$it", topic, QuestionType.UNEXPECTED, 4)) }
        }
        listOf("rp_movie", "rp_travel").forEach { topic ->
            add(q("${topic}_ask", topic, QuestionType.ROLEPLAY_ASK, 3))
            add(q("${topic}_solve", topic, QuestionType.ROLEPLAY_SOLVE, 4))
            add(q("${topic}_exp", topic, QuestionType.ROLEPLAY_EXPERIENCE, 4))
        }
    }

    @Test
    fun `composition follows opic structure`() {
        val result = ExamComposer.compose(
            pool = pool,
            surveyTopicIds = listOf("home", "music", "park"),
            target = TargetGrade.IM2,
            randomSeed = 42L,
        ).questions

        // 자기소개로 시작
        assertEquals(QuestionType.SELF_INTRODUCTION, result.first().type)
        // 1(소개) + 2콤보×3 + 돌발3 + 롤플레이3 = 13
        assertEquals(13, result.size)
        // 중복 없음
        assertEquals(result.size, result.map { it.id }.distinct().size)
        // 롤플레이 3단계가 같은 시나리오
        val roleplays = result.filter {
            it.type in listOf(
                QuestionType.ROLEPLAY_ASK,
                QuestionType.ROLEPLAY_SOLVE,
                QuestionType.ROLEPLAY_EXPERIENCE,
            )
        }
        assertEquals(3, roleplays.size)
        assertEquals(1, roleplays.map { it.topicId }.distinct().size)
        // 돌발 3문항이 같은 주제
        val unexpected = result.filter { it.type == QuestionType.UNEXPECTED }
        assertEquals(3, unexpected.size)
        assertEquals(1, unexpected.map { it.topicId }.distinct().size)
    }

    @Test
    fun `difficulty stays in target range when available`() {
        val result = ExamComposer.compose(
            pool = pool,
            surveyTopicIds = listOf("home", "music", "park"),
            target = TargetGrade.IM2,
            randomSeed = 7L,
        ).questions

        val surveyQuestions = result.filter {
            it.type in listOf(
                QuestionType.DESCRIPTION,
                QuestionType.ROUTINE,
                QuestionType.EXPERIENCE,
                QuestionType.COMPARISON,
            )
        }
        surveyQuestions.forEach {
            assertTrue(
                "difficulty ${it.difficulty} out of IM2 range",
                it.difficulty in TargetGrade.IM2.questionDifficulty,
            )
        }
    }

    @Test
    fun `same seed gives same composition`() {
        val a = ExamComposer.compose(pool, listOf("home", "music"), TargetGrade.IM2, 99L)
        val b = ExamComposer.compose(pool, listOf("home", "music"), TargetGrade.IM2, 99L)
        assertEquals(a.questions.map { it.id }, b.questions.map { it.id })
    }

    @Test
    fun `adjust remaining shifts difficulty when replacement exists`() {
        val answered = listOf(pool.first())
        val remaining = listOf(q("home_desc_3", "home", QuestionType.DESCRIPTION, 3))
        val adjusted = ExamComposer.adjustRemaining(
            pool = pool,
            answered = answered,
            remaining = remaining,
            adjust = DifficultyAdjust.HARDER,
            randomSeed = 1L,
        )
        assertEquals(1, adjusted.size)
        assertEquals(4, adjusted.first().difficulty)
        assertEquals(QuestionType.DESCRIPTION, adjusted.first().type)
        assertEquals("home", adjusted.first().topicId)
    }

    @Test
    fun `report aggregation is conservative and collects weaknesses`() {
        fun graded(idx: Int, grade: OpicGrade, tags: List<String>) = GradedAnswer(
            orderIndex = idx,
            question = pool.first(),
            feedback = AnswerFeedback(
                transcript = "答案",
                estimatedGrade = grade,
                axes = listOf(AxisScore(RubricAxis.FLUENCY, 5, "")),
                weaknessTags = tags,
            ),
        )

        val report = ExamReportAggregator.aggregate(
            listOf(
                graded(0, OpicGrade.IM2, listOf("성조", "연결어")),
                graded(1, OpicGrade.IM3, listOf("성조")),
                graded(2, OpicGrade.IM1, listOf("성조", "어순")),
            ),
            TargetGrade.IM2,
        )

        assertEquals(OpicGrade.IM2, report.overallGrade)
        assertEquals(OpicGrade.IM1, report.gradeLow)
        assertEquals(OpicGrade.IM3, report.gradeHigh)
        assertTrue(report.passedTarget)
        assertEquals("성조", report.topWeaknesses.first())
        assertEquals(5.0, report.axisAverages[RubricAxis.FLUENCY] ?: 0.0, 0.001)
    }
}
