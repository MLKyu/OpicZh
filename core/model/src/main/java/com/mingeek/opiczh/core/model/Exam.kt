package com.mingeek.opiczh.core.model

import kotlinx.serialization.Serializable

/** 시험 중간(7번 문항 이후) 난이도 재선택 */
enum class DifficultyAdjust(val ko: String, val shift: Int) {
    EASIER("더 쉬운 질문", -1),
    SAME("비슷한 질문", 0),
    HARDER("더 어려운 질문", +1),
}

enum class ExamStatus { IN_PROGRESS, GRADED, ABORTED }

/** 시험 한 회차의 요약 (히스토리/대시보드용) */
data class ExamSummary(
    val sessionId: String,
    val startedAtEpochMs: Long,
    val targetGrade: TargetGrade,
    val status: ExamStatus,
    val overallGrade: OpicGrade?,
    val questionCount: Int,
)

/** 채점 완료된 답변 (리포트 표시 단위) */
data class GradedAnswer(
    val orderIndex: Int,
    val question: Question,
    val feedback: AnswerFeedback,
)

/**
 * 채점이 끝나지 않은 보관 세션 (홈 '채점 대기함' 표시 단위).
 * 답변 녹음·문항은 전부 저장돼 있으므로 앱이 죽든 채점이 실패하든 유실되지 않는다.
 */
data class PendingGrading(
    val sessionId: String,
    val startedAtEpochMs: Long,
    val targetGrade: TargetGrade,
    val answerCount: Int,
    /** 정식(클라우드 6축) 채점 완료 수 — 임시 채점은 포함하지 않는다 */
    val gradedCount: Int,
    /** 온디바이스 임시 채점 수 — '이어서 채점'하면 정식으로 덮어써진다 */
    val provisionalCount: Int = 0,
)

/** 저장된 답변 한 건 (채점 재개용). 채점 전이면 [feedback]이 null */
data class StoredExamAnswer(
    val answerId: String,
    val orderIndex: Int,
    val question: Question,
    val audioPath: String?,
    val feedback: AnswerFeedback?,
)

/** 전체 시험 리포트 */
data class ExamReport(
    val overallGrade: OpicGrade,
    val gradeLow: OpicGrade,
    val gradeHigh: OpicGrade,
    val axisAverages: Map<RubricAxis, Double>,
    val topWeaknesses: List<String>,
    val passedTarget: Boolean,
)

/** 채점 결과들을 결정적으로 집계한다 (LLM 재호출 없이). */
object ExamReportAggregator {

    fun aggregate(answers: List<GradedAnswer>, target: TargetGrade): ExamReport {
        require(answers.isNotEmpty()) { "채점된 답변이 없습니다" }

        val ranks = answers.map { it.feedback.estimatedGrade.rank }.sorted()
        val meanRank = ranks.average()
        // 보수적 추정: 평균과 중앙값 중 낮은 쪽
        val medianRank = ranks[ranks.size / 2]
        val overallRank = minOf(meanRank, medianRank.toDouble())
        val overall = gradeFromRank(overallRank)

        val low = gradeFromRank(ranks.first().toDouble())
        val high = gradeFromRank(ranks.last().toDouble())

        // 점수가 하나도 없는 축은 맵에서 제외한다 — 4축 임시 채점(발음·유창성 미평가)이
        // 섞였을 때 0.0이 평균을 오염시키지 않게. 혼합 세트에서는 해당 축을 평가받은
        // 답변들만의 평균이 된다.
        val axisAverages = RubricAxis.entries.mapNotNull { axis ->
            val scores = answers.flatMap { a -> a.feedback.axes.filter { it.axis == axis } }
                .map { it.score }
            scores.takeIf { it.isNotEmpty() }?.let { axis to it.average() }
        }.toMap()

        val topWeaknesses = answers.flatMap { it.feedback.weaknessTags }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .take(5)
            .map { it.key }

        return ExamReport(
            overallGrade = overall,
            gradeLow = low,
            gradeHigh = high,
            axisAverages = axisAverages,
            topWeaknesses = topWeaknesses,
            passedTarget = overall.rank >= target.gradeFloor.rank,
        )
    }

    private fun gradeFromRank(rank: Double): OpicGrade {
        val clamped = rank.toInt().coerceIn(0, OpicGrade.entries.size - 1)
        return OpicGrade.entries.first { it.rank == clamped }
    }
}

/**
 * 문제은행 풀에서 실전 구성(자기소개 → 서베이 콤보 → 돌발 콤보 → 롤플레이 세트)의
 * 시험지를 결정적으로(random seed) 조립한다. 순수 함수 — 단위 테스트 대상.
 */
object ExamComposer {

    data class Composition(val questions: List<Question>)

    private val COMBO_TYPES = listOf(
        listOf(QuestionType.DESCRIPTION, QuestionType.ROUTINE, QuestionType.EXPERIENCE),
        listOf(QuestionType.DESCRIPTION, QuestionType.EXPERIENCE, QuestionType.COMPARISON),
    )

    private val ROLEPLAY_TYPES = listOf(
        QuestionType.ROLEPLAY_ASK,
        QuestionType.ROLEPLAY_SOLVE,
        QuestionType.ROLEPLAY_EXPERIENCE,
    )

    fun compose(
        pool: List<Question>,
        surveyTopicIds: List<String>,
        target: TargetGrade,
        randomSeed: Long,
        surveyComboCount: Int = 2,
    ): Composition {
        val random = kotlin.random.Random(randomSeed)
        val difficultyRange = target.questionDifficulty
        val result = mutableListOf<Question>()

        fun pick(topicId: String?, type: QuestionType, exclude: Set<String>): Question? {
            val candidates = pool.asSequence()
                .filter { topicId == null || it.topicId == topicId }
                .filter { it.type == type }
                .filter { it.id !in exclude }
                .toList()
            if (candidates.isEmpty()) return null
            // 목표 난이도 범위 내 우선, 없으면 가장 가까운 난이도
            val inRange = candidates.filter { it.difficulty in difficultyRange }
            val chosenPool = inRange.ifEmpty {
                val nearest = candidates.minOf {
                    minOf(
                        kotlin.math.abs(it.difficulty - difficultyRange.first),
                        kotlin.math.abs(it.difficulty - difficultyRange.last),
                    )
                }
                candidates.filter {
                    minOf(
                        kotlin.math.abs(it.difficulty - difficultyRange.first),
                        kotlin.math.abs(it.difficulty - difficultyRange.last),
                    ) == nearest
                }
            }
            return chosenPool.random(random)
        }

        val used = mutableSetOf<String>()
        fun add(question: Question?) {
            if (question != null && used.add(question.id)) result.add(question)
        }

        // 1. 자기소개
        add(pick(null, QuestionType.SELF_INTRODUCTION, used))

        // 2. 서베이 주제 콤보
        val shuffledTopics = surveyTopicIds.shuffled(random)
        shuffledTopics.take(surveyComboCount).forEach { topicId ->
            val combo = COMBO_TYPES.random(random)
            combo.forEach { type -> add(pick(topicId, type, used)) }
        }

        // 3. 돌발 콤보 (서베이 외 주제)
        val unexpectedTopics = pool.asSequence()
            .filter { it.type == QuestionType.UNEXPECTED }
            .map { it.topicId }
            .distinct()
            .toList()
        if (unexpectedTopics.isNotEmpty()) {
            val topic = unexpectedTopics.random(random)
            pool.filter { it.topicId == topic && it.type == QuestionType.UNEXPECTED && it.id !in used }
                .shuffled(random)
                .take(3)
                .forEach { add(it) }
        }

        // 4. 롤플레이 세트 (같은 시나리오의 3단계)
        val roleplayTopics = pool.asSequence()
            .filter { it.type in ROLEPLAY_TYPES }
            .map { it.topicId }
            .distinct()
            .toList()
        if (roleplayTopics.isNotEmpty()) {
            val scenario = roleplayTopics.random(random)
            ROLEPLAY_TYPES.forEach { type -> add(pick(scenario, type, used)) }
        }

        return Composition(result)
    }

    /** 중간 난이도 재선택: 남은 문항을 난이도 이동해 다시 뽑는다 */
    fun adjustRemaining(
        pool: List<Question>,
        answered: List<Question>,
        remaining: List<Question>,
        adjust: DifficultyAdjust,
        randomSeed: Long,
    ): List<Question> {
        if (adjust == DifficultyAdjust.SAME) return remaining
        val random = kotlin.random.Random(randomSeed)
        val usedIds = (answered + remaining).map { it.id }.toMutableSet()
        return remaining.map { original ->
            val targetDifficulty = (original.difficulty + adjust.shift).coerceIn(1, 6)
            val replacement = pool.filter {
                it.topicId == original.topicId &&
                    it.type == original.type &&
                    it.difficulty == targetDifficulty &&
                    it.id !in usedIds
            }.randomOrNull(random)
            if (replacement != null) {
                usedIds.add(replacement.id)
                replacement
            } else {
                original
            }
        }
    }
}
