package com.mingeek.opiczh.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface QuestionDao {

    @Query("SELECT COUNT(*) FROM questions")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTopics(topics: List<TopicEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuestions(questions: List<QuestionEntity>)

    @Query("SELECT * FROM questions")
    suspend fun allQuestions(): List<QuestionEntity>

    @Query("SELECT * FROM questions WHERE topicId = :topicId ORDER BY difficulty")
    suspend fun questionsByTopic(topicId: String): List<QuestionEntity>

    @Query("SELECT * FROM topics WHERE survey = 1 ORDER BY nameKo")
    suspend fun surveyTopics(): List<TopicEntity>

    @Query("SELECT * FROM topics")
    suspend fun allTopics(): List<TopicEntity>
}

@Dao
interface ExamDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ExamSessionEntity)

    @Update
    suspend fun updateSession(session: ExamSessionEntity)

    @Query("SELECT * FROM exam_sessions WHERE id = :id")
    suspend fun session(id: String): ExamSessionEntity?

    @Query("SELECT * FROM exam_sessions ORDER BY startedAtEpochMs DESC")
    fun sessionsFlow(): Flow<List<ExamSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAnswer(answer: ExamAnswerEntity)

    @Query("SELECT * FROM exam_answers WHERE sessionId = :sessionId ORDER BY orderIndex")
    suspend fun answers(sessionId: String): List<ExamAnswerEntity>

    @Query("UPDATE exam_answers SET feedbackJson = :feedbackJson WHERE id = :answerId")
    suspend fun updateAnswerFeedback(answerId: String, feedbackJson: String)
}

@Dao
interface SrsDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCards(cards: List<SrsCardEntity>)

    @Update
    suspend fun updateCard(card: SrsCardEntity)

    @Query("SELECT * FROM srs_cards WHERE dueAtEpochMs <= :now ORDER BY dueAtEpochMs LIMIT :limit")
    suspend fun dueCards(now: Long, limit: Int = 30): List<SrsCardEntity>

    @Query("SELECT COUNT(*) FROM srs_cards WHERE dueAtEpochMs <= :now")
    fun dueCountFlow(now: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM srs_cards")
    suspend fun totalCount(): Int

    @Query("DELETE FROM srs_cards WHERE id = :cardId")
    suspend fun deleteCard(cardId: String)
}
