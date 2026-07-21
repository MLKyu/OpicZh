package com.mingeek.opiczh.core.data.exam

import com.mingeek.opiczh.core.data.db.ExamAnswerEntity
import com.mingeek.opiczh.core.data.db.ExamDao
import com.mingeek.opiczh.core.data.db.ExamSessionEntity
import com.mingeek.opiczh.core.data.db.QuestionDao
import com.mingeek.opiczh.core.data.db.QuestionEntity
import com.mingeek.opiczh.core.model.PendingGrading
import com.mingeek.opiczh.core.model.StoredExamAnswer
import com.mingeek.opiczh.core.data.seed.SeedImporter
import com.mingeek.opiczh.core.model.AnswerFeedback
import com.mingeek.opiczh.core.model.ExamReport
import com.mingeek.opiczh.core.model.ExamStatus
import com.mingeek.opiczh.core.model.ExamSummary
import com.mingeek.opiczh.core.model.GradedAnswer
import com.mingeek.opiczh.core.model.OpicGrade
import com.mingeek.opiczh.core.model.Question
import com.mingeek.opiczh.core.model.QuestionType
import com.mingeek.opiczh.core.model.RubricAxis
import com.mingeek.opiczh.core.model.TargetGrade
import com.mingeek.opiczh.core.model.Topic
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class StoredReport(
    val overallGrade: OpicGrade,
    val gradeLow: OpicGrade,
    val gradeHigh: OpicGrade,
    val axisAverages: Map<RubricAxis, Double>,
    val topWeaknesses: List<String>,
    val passedTarget: Boolean,
)

/** 문제은행 조회 + 시험 세션/답변/리포트 영속화 */
@Singleton
class ExamRepository @Inject constructor(
    private val questionDao: QuestionDao,
    private val examDao: ExamDao,
    private val seedImporter: SeedImporter,
) {

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun surveyTopics(): List<Topic> {
        seedImporter.ensureSeeded()
        return questionDao.surveyTopics().map { Topic(it.id, it.nameKo, it.nameZh, it.survey) }
    }

    suspend fun questionPool(): List<Question> {
        seedImporter.ensureSeeded()
        return questionDao.allQuestions().map { it.toDomain() }
    }

    suspend fun createSession(
        targetGrade: TargetGrade,
        selfAssessment: Int,
    ): String {
        val id = UUID.randomUUID().toString()
        examDao.insertSession(
            ExamSessionEntity(
                id = id,
                startedAtEpochMs = System.currentTimeMillis(),
                targetGrade = targetGrade.name,
                selfAssessment = selfAssessment,
                status = ExamStatus.IN_PROGRESS.name,
            ),
        )
        return id
    }

    suspend fun saveAnswer(
        sessionId: String,
        orderIndex: Int,
        question: Question,
        audioPath: String?,
    ): String {
        val answerId = UUID.randomUUID().toString()
        examDao.insertAnswer(
            ExamAnswerEntity(
                id = answerId,
                sessionId = sessionId,
                orderIndex = orderIndex,
                questionId = question.id,
                questionJson = json.encodeToString(Question.serializer(), question),
                audioPath = audioPath,
            ),
        )
        return answerId
    }

    suspend fun saveAnswerFeedback(answerId: String, feedback: AnswerFeedback) {
        examDao.updateAnswerFeedback(
            answerId,
            json.encodeToString(AnswerFeedback.serializer(), feedback),
        )
    }

    suspend fun completeSession(sessionId: String, report: ExamReport) {
        val session = examDao.session(sessionId) ?: return
        examDao.updateSession(
            session.copy(
                status = ExamStatus.GRADED.name,
                overallGrade = report.overallGrade.name,
                reportJson = json.encodeToString(
                    StoredReport.serializer(),
                    StoredReport(
                        overallGrade = report.overallGrade,
                        gradeLow = report.gradeLow,
                        gradeHigh = report.gradeHigh,
                        axisAverages = report.axisAverages,
                        topWeaknesses = report.topWeaknesses,
                        passedTarget = report.passedTarget,
                    ),
                ),
            ),
        )
    }

    suspend fun abortSession(sessionId: String) {
        val session = examDao.session(sessionId) ?: return
        examDao.updateSession(session.copy(status = ExamStatus.ABORTED.name))
    }

    /** 채점이 끝나지 않은 보관 세션 (홈 '채점 대기함') */
    fun pendingGradingFlow(): Flow<List<PendingGrading>> =
        examDao.pendingGradingFlow(
            inProgressStatus = ExamStatus.IN_PROGRESS.name,
            abortedStatus = ExamStatus.ABORTED.name,
        ).map { rows ->
            rows.map { row ->
                PendingGrading(
                    sessionId = row.sessionId,
                    startedAtEpochMs = row.startedAtEpochMs,
                    targetGrade = TargetGrade.entries.firstOrNull { it.name == row.targetGrade }
                        ?: TargetGrade.DEFAULT,
                    answerCount = row.answerCount,
                    gradedCount = row.gradedCount,
                    provisionalCount = row.provisionalCount,
                )
            }
        }

    /** 채점 재개용: 세션의 목표 등급. 세션이 없으면 null */
    suspend fun sessionTargetGrade(sessionId: String): TargetGrade? =
        examDao.session(sessionId)?.let { s ->
            TargetGrade.entries.firstOrNull { it.name == s.targetGrade } ?: TargetGrade.DEFAULT
        }

    /** 채점 재개용: 세션의 저장된 답변 전체 (채점된 것은 feedback 포함) */
    suspend fun sessionAnswers(sessionId: String): List<StoredExamAnswer> =
        examDao.answers(sessionId).map { entity ->
            StoredExamAnswer(
                answerId = entity.id,
                orderIndex = entity.orderIndex,
                question = json.decodeFromString(Question.serializer(), entity.questionJson),
                audioPath = entity.audioPath,
                feedback = entity.feedbackJson?.let { raw ->
                    runCatching {
                        json.decodeFromString(AnswerFeedback.serializer(), raw)
                    }.getOrNull()
                },
            )
        }

    suspend fun gradedAnswers(sessionId: String): List<GradedAnswer> =
        examDao.answers(sessionId).mapNotNull { entity ->
            val feedback = entity.feedbackJson ?: return@mapNotNull null
            GradedAnswer(
                orderIndex = entity.orderIndex,
                question = json.decodeFromString(Question.serializer(), entity.questionJson),
                feedback = json.decodeFromString(AnswerFeedback.serializer(), feedback),
            )
        }

    fun sessionSummaries(): Flow<List<ExamSummary>> =
        examDao.sessionsFlow().map { sessions ->
            sessions.map { s ->
                ExamSummary(
                    sessionId = s.id,
                    startedAtEpochMs = s.startedAtEpochMs,
                    targetGrade = TargetGrade.entries.firstOrNull { it.name == s.targetGrade }
                        ?: TargetGrade.DEFAULT,
                    status = ExamStatus.entries.firstOrNull { it.name == s.status }
                        ?: ExamStatus.ABORTED,
                    overallGrade = s.overallGrade?.let { g ->
                        OpicGrade.entries.firstOrNull { it.name == g }
                    },
                    questionCount = 0,
                )
            }
        }

    private fun QuestionEntity.toDomain(): Question = Question(
        id = id,
        topicId = topicId,
        type = QuestionType.entries.firstOrNull { it.name == type } ?: QuestionType.UNEXPECTED,
        difficulty = difficulty,
        zh = zh,
        pinyin = pinyin,
        ko = ko,
    )
}
