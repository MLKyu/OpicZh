package com.mingeek.opiczh.core.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "topics")
data class TopicEntity(
    @PrimaryKey val id: String,
    val nameKo: String,
    val nameZh: String,
    val survey: Boolean,
)

@Entity(
    tableName = "questions",
    indices = [Index("topicId"), Index("type")],
)
data class QuestionEntity(
    @PrimaryKey val id: String,
    val topicId: String,
    /** QuestionType.name */
    val type: String,
    val difficulty: Int,
    val zh: String,
    val pinyin: String?,
    val ko: String?,
)

@Entity(tableName = "exam_sessions")
data class ExamSessionEntity(
    @PrimaryKey val id: String,
    val startedAtEpochMs: Long,
    /** TargetGrade.name */
    val targetGrade: String,
    val selfAssessment: Int,
    /** ExamStatus.name */
    val status: String,
    /** OpicGrade.name (채점 후) */
    val overallGrade: String? = null,
    /** ExamReport 직렬화 JSON (채점 후) */
    val reportJson: String? = null,
)

@Entity(
    tableName = "exam_answers",
    indices = [Index("sessionId")],
)
data class ExamAnswerEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val orderIndex: Int,
    val questionId: String,
    /** 문항 스냅샷 (문제은행이 바뀌어도 리포트 보존) */
    val questionJson: String,
    val audioPath: String? = null,
    /** AnswerFeedback 직렬화 JSON (채점 후) */
    val feedbackJson: String? = null,
)

@Entity(
    tableName = "srs_cards",
    indices = [Index("dueAtEpochMs"), Index(value = ["back"], unique = true)],
)
data class SrsCardEntity(
    @PrimaryKey val id: String,
    val front: String,
    val back: String,
    val pinyin: String?,
    val sourceTag: String,
    val dueAtEpochMs: Long,
    val intervalDays: Double,
    val ease: Double,
    val reps: Int,
)
